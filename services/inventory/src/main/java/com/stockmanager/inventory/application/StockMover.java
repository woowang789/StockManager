package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.infrastructure.StockMovementRepository;
import com.stockmanager.inventory.infrastructure.StockRepository;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class StockMover {

    private static final Comparator<StockChange> UPDATE_ORDER = Comparator
        .comparing(StockChange::locationCode)
        .thenComparing(StockChange::productId)
        .thenComparing(StockChange::state);

    private final StockRepository stockRepository;
    private final StockMovementRepository stockMovementRepository;

    StockMover(StockRepository stockRepository, StockMovementRepository stockMovementRepository) {
        this.stockRepository = stockRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public long move(StockMovementCommand command, Instant occurredAt) {
        long movementId = stockMovementRepository.insertMovement(command, occurredAt, Instant.now());

        List<StockChange> changes = command.changes().stream().sorted(UPDATE_ORDER).toList();
        for (StockChange change : changes) {
            int balanceAfter = change.delta() > 0
                ? stockRepository.increase(change.locationCode(), change.productId(), change.state(), change.delta())
                : stockRepository.decrease(change.locationCode(), change.productId(), change.state(), -change.delta());
            stockMovementRepository.insertEntry(movementId, change, balanceAfter);
        }
        return movementId;
    }
}
