package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.ReserveCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.domain.StockState;
import com.stockmanager.inventory.infrastructure.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final StockMover stockMover;

    public ReservationService(ReservationRepository reservationRepository, StockMover stockMover) {
        this.reservationRepository = reservationRepository;
        this.stockMover = stockMover;
    }

    @Transactional
    public long reserve(ReserveCommand command, String actor) {
        List<StockChange> changes = new ArrayList<>();
        for (ReserveCommand.Item item : command.items()) {
            reservationRepository.insert(command, item);
            changes.add(new StockChange(command.locationCode(), item.productId(), StockState.AVAILABLE, -item.quantity()));
            changes.add(new StockChange(command.locationCode(), item.productId(), StockState.RESERVED, item.quantity()));
        }

        StockMovementCommand movement = new StockMovementCommand(
            MovementType.RESERVE, command.refType(), command.refId(), null, actor, changes
        );
        return stockMover.move(movement, Instant.now());
    }
}
