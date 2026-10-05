package com.stockmanager.inventory.application;

import com.stockmanager.common.event.ReputawayStored;
import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.domain.StockState;
import com.stockmanager.inventory.infrastructure.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReputawayService {

    private static final Logger log = LoggerFactory.getLogger(ReputawayService.class);

    private static final String REF_TYPE = "REPUTAWAY";

    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    ReputawayService(StockMovementRepository stockMovementRepository, StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public void store(ReputawayStored event, String actor) {
        StockMovementCommand movement = toMovement(event,actor);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            log.info("이미 반영한 재적치입니다: {}", movement.idempotencyKey());
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> stockMover.move(movement, event.occurredAt()));
        } catch (DuplicateKeyException exception) {
            log.info("이미 반영한 재적치입니다: {}", movement.idempotencyKey());
        }
    }

    private StockMovementCommand toMovement(ReputawayStored event, String actor) {
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item -> {
            changes.add(new StockChange(
                event.locationCode(), item.productId(), StockState.PUTAWAY_WAIT, -item.quantity()));
            changes.add(new StockChange(
                event.locationCode(), item.productId(), StockState.AVAILABLE, item.quantity()));
        });
        return new StockMovementCommand(MovementType.PUTAWAY, REF_TYPE, String.valueOf(event.reputawayId()),
            null, actor, changes);
    }
}

