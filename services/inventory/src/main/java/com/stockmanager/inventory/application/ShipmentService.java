package com.stockmanager.inventory.application;

import com.stockmanager.common.event.ShipmentCanceled;
import com.stockmanager.common.event.ShipmentShipped;
import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.MovementType;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import com.stockmanager.inventory.domain.StockState;
import com.stockmanager.inventory.infrastructure.ReservationRepository;
import com.stockmanager.inventory.infrastructure.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShipmentService {

    private static final String REF_TYPE = "ORDER";

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ReservationRepository reservationRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    ShipmentService(ReservationRepository reservationRepository, StockMovementRepository stockMovementRepository,
                    StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.reservationRepository = reservationRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public void apply(ShipmentShipped event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                reservationRepository.consume(REF_TYPE, event.orderNo());
                stockMover.move(movement, event.occurredAt());
            });
        } catch (DuplicateKeyException exception) {
            log.info("이미 반영한 출하입니다: {}", movement.idempotencyKey());
        }
    }

    public void release(ShipmentCanceled event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                reservationRepository.release(REF_TYPE, event.orderNo());
                stockMover.move(movement, event.occurredAt());
                if (!event.shortages().isEmpty()) {
                    stockMover.move(toShortageMovement(event), event.occurredAt());
                }
            });
        } catch (DuplicateKeyException exception) {
            if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isEmpty()) {
                throw exception;
            }
            log.info("이미 반영된 취소입니다: {}", movement.idempotencyKey());
        }
    }

    private StockMovementCommand toMovement(ShipmentCanceled event) {
        StockState returnTo = event.picked() ? StockState.PUTAWAY_WAIT : StockState.AVAILABLE;
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item -> {
            changes.add(new StockChange(event.locationCode(), item.productId(), StockState.RESERVED, -item.quantity()));
            changes.add(new StockChange(event.locationCode(), item.productId(), returnTo, item.quantity()));
        });
        return new StockMovementCommand(MovementType.RELEASE, REF_TYPE, event.orderNo(), null, "wms", changes);
    }

    private StockMovementCommand toShortageMovement(ShipmentCanceled event) {
        StockState from = event.picked() ? StockState.PUTAWAY_WAIT : StockState.AVAILABLE;
        List<StockChange> changes = event.shortages().stream()
            .map(shortage -> new StockChange(
                event.locationCode(), shortage.productId(), from, -shortage.quantity()))
            .toList();

        return new StockMovementCommand(MovementType.ADJUST, REF_TYPE, event.orderNo(),
            AdjustmentReason.SHORTAGE.name(), "wms", changes);
    }

    private StockMovementCommand toMovement(ShipmentShipped event) {
        List<StockChange> changes = event.items().stream()
            .map(item -> new StockChange(
                event.locationCode(), item.productId(), StockState.RESERVED, -item.quantity()
            )).toList();
        return new StockMovementCommand(MovementType.SHIP, REF_TYPE, event.orderNo(), null, "wms", changes);
    }
}
