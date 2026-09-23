package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.ReserveCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.domain.StockState;
import com.stockmanager.inventory.infrastructure.ReservationRepository;
import com.stockmanager.inventory.infrastructure.StockMovementRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ReservationService {

    private static final int MAX_ATTEMPTS = 5;

    private final ReservationRepository reservationRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    public ReservationService(ReservationRepository reservationRepository, StockMovementRepository stockMovementRepository, StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.reservationRepository = reservationRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public long reserve(ReserveCommand command, String actor) {
        StockMovementCommand movement = toMovement(command, actor);
        for(int attempt = 1; ; attempt ++){
            try {
                return reserveOnce(command, movement);
            } catch (CannotAcquireLockException exception) {
                if (attempt == MAX_ATTEMPTS){
                    throw exception;
                }
            }
        }
    }

    private long reserveOnce(ReserveCommand command, StockMovementCommand movement) {
        Optional<Long> processed = stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey());
        if (processed.isPresent()) {
            return processed.get();
        }
        try {
            return transactionTemplate.execute(status -> {
                command.items().forEach(item -> reservationRepository.insert(command, item));
                return stockMover.move(movement, Instant.now());
            });
        } catch (DuplicateKeyException exception) {
            return stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey())
                .orElseThrow(() -> exception);
        }
    }

    private StockMovementCommand toMovement(ReserveCommand command, String actor) {
        List<StockChange> changes = new ArrayList<>();
        for (ReserveCommand.Item item : command.items()) {
            changes.add(new StockChange(command.locationCode(), item.productId(), StockState.AVAILABLE, -item.quantity()));
            changes.add(new StockChange(command.locationCode(), item.productId(), StockState.RESERVED, item.quantity()));
        }

        return new StockMovementCommand(
            MovementType.RESERVE, command.refType(), command.refId(), null, actor, changes);
    }
}
