package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.PosDeductionCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.domain.StockState;
import com.stockmanager.inventory.infrastructure.StockMovementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PosDeductionService {

    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    PosDeductionService(StockMovementRepository stockMovementRepository, StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public long deduct(PosDeductionCommand command, String actor) {
        StockMovementCommand movement = toMovement(command, actor);
        return DeadlockRetry.run(() -> deductOnce(movement));
    }

    private long deductOnce(StockMovementCommand movement) {
        Optional<Long> processed = stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey());
        if (processed.isPresent()) {
            return processed.get();
        }

        try {
            return transactionTemplate.execute(status -> stockMover.move(movement, Instant.now()));
        } catch (DuplicateKeyException exception) {
            return stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey())
                .orElseThrow(() -> exception);
        }
    }

    private StockMovementCommand toMovement(PosDeductionCommand command, String actor) {
        List<StockChange> changes = command.items().stream()
            .map(item -> new StockChange(
                command.locationCode(), item.productId(), StockState.AVAILABLE, -item.quantity()))
            .toList();
        return new StockMovementCommand(
            MovementType.POS_SALE, command.refType(), command.refId(), null, actor, changes);
    }
}
