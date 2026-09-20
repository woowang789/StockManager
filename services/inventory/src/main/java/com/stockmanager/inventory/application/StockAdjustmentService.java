package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class StockAdjustmentService {

    private final StockMover stockMover;

    StockAdjustmentService(StockMover stockMover) {
        this.stockMover = stockMover;
    }

    @Transactional
    public long adjust(StockChange change, AdjustmentReason reason, String actor) {
        StockMovementCommand command = new StockMovementCommand(
            MovementType.ADJUST, null, null, reason.name() , actor, List.of(change));
        return stockMover.move(command, Instant.now());
    }
}
