package com.stockmanager.inventory.application;

import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.event.InboundStored;
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
public class InboundService {

    private static final Logger log = LoggerFactory.getLogger(InboundService.class);

    private static final String REF_TYPE = "INBOUND";

    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    InboundService(StockMovementRepository stockMovementRepository, StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public void apply(InboundInspected event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            log.info("이미 반영한 입고입니다: {}", movement.idempotencyKey());
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> stockMover.move(movement, event.occurredAt()));
        } catch (DuplicateKeyException exception) {
            log.info("이미 반영한 입고입니다: {}", movement.idempotencyKey());
        }
    }

    public void store(InboundStored event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            log.info("이미 반영한 적치입니다: {}", movement.idempotencyKey());
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> stockMover.move(movement, event.occurredAt()));
        } catch (DuplicateKeyException exception) {
            log.info("이미 반영한 적치입니다: {}", movement.idempotencyKey());
        }
    }

    private StockMovementCommand toMovement(InboundStored event) {
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item -> {
            changes.add(new StockChange(
                event.locationCode(), item.productId(), StockState.PUTAWAY_WAIT, -item.quantity()));
            changes.add(new StockChange(
                event.locationCode(), item.productId(), StockState.AVAILABLE, item.quantity()));
        });
        return new StockMovementCommand(MovementType.PUTAWAY, REF_TYPE, String.valueOf(event.inboundId()),
            null, "wms", changes);
    }

    private StockMovementCommand toMovement(InboundInspected event) {
        StockState goodState = event.putawayPending() ? StockState.PUTAWAY_WAIT : StockState.AVAILABLE;
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item ->{
            if(item.goodQuantity() > 0) {
                changes.add(new StockChange(
                    event.locationCode(), item.productId(), goodState, item.goodQuantity()));
            }
            if (item.defectiveQuantity() > 0) {
                changes.add(new StockChange(
                    event.locationCode(), item.productId(), StockState.DEFECTIVE, item.defectiveQuantity()));
            }
        });
        return new StockMovementCommand(MovementType.RECEIVE, REF_TYPE, String.valueOf(event.inboundId()),
            null, "wms", changes);
    }
}
