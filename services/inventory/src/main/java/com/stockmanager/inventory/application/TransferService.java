package com.stockmanager.inventory.application;

import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.common.event.TransferReceived;
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
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String REF_TYPE = "TRANSFER";

    private final ReservationRepository reservationRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMover stockMover;
    private final TransactionTemplate transactionTemplate;

    TransferService(ReservationRepository reservationRepository, StockMovementRepository stockMovementRepository,
                    StockMover stockMover, TransactionTemplate transactionTemplate) {
        this.reservationRepository = reservationRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMover = stockMover;
        this.transactionTemplate = transactionTemplate;
    }

    public void dispatch(TransferDispatched event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            log.info("이미 반영한 이동입니다: {}", movement.idempotencyKey());
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                reservationRepository.consume(REF_TYPE, String.valueOf(event.transferId()));
                stockMover.move(movement, event.occurredAt());
            });
        } catch (DuplicateKeyException exception) {
            log.info("이미 반영한 이동입니다: {}", movement.idempotencyKey());
        }
    }

    public void receive(TransferReceived event) {
        StockMovementCommand movement = toMovement(event);
        if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isPresent()) {
            log.info("이미 반영한 도착입니다: {}", movement.idempotencyKey());
            return;
        }
        List<StockChange> losses = toLosses(event, sentBy(event));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                stockMover.move(movement, event.occurredAt());
                if (!losses.isEmpty()) {
                    stockMover.move(new StockMovementCommand(MovementType.ADJUST, REF_TYPE,
                            String.valueOf(event.transferId()), AdjustmentReason.TRANSIT_LOSS.name(), "wms", losses),
                        event.occurredAt());
                }
            });
        } catch (DuplicateKeyException exception) {
            if (stockMovementRepository.findIdByIdempotencyKey(movement.idempotencyKey()).isEmpty()) {
                throw exception;
            }
            log.info("이미 반영한 도착입니다: {}", movement.idempotencyKey());
        }
    }

    private StockMovementCommand toMovement(TransferReceived event) {
        StockState goodState = StockState.forReceivedGoods(event.toLocationCode());
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item -> {
            int received = item.goodQuantity() + item.defectiveQuantity();
            if (received > 0) {
                changes.add(new StockChange(event.toLocationCode(), item.productId(), StockState.IN_TRANSIT, -received));
            }
            if (item.goodQuantity() > 0) {
                changes.add(new StockChange(event.toLocationCode(), item.productId(), goodState, item.goodQuantity()));
            }
            if (item.defectiveQuantity() > 0) {
                changes.add(new StockChange(
                    event.toLocationCode(), item.productId(), StockState.DEFECTIVE, item.defectiveQuantity()));
            }
        });
        return new StockMovementCommand(MovementType.TRANSFER_RECEIVE, REF_TYPE, String.valueOf(event.transferId()),
            null, "wms", changes);
    }

    private Map<Long, Integer> sentBy(TransferReceived event) {
        String dispatchKey = StockMovementCommand.idempotencyKey(
            REF_TYPE, String.valueOf(event.transferId()), MovementType.DISPATCH);
        return stockMovementRepository.findChanges(dispatchKey).stream()
            .filter(change -> change.locationCode().equals(event.toLocationCode())
                && change.state() == StockState.IN_TRANSIT)
            .collect(Collectors.toMap(StockChange::productId, StockChange::delta));
    }

    private List<StockChange> toLosses(TransferReceived event, Map<Long, Integer> sent) {
        List<StockChange> losses = new ArrayList<>();
        event.items().forEach(item ->{
            Integer quantity = sent.get(item.productId());
            if (quantity == null) {
                throw new IllegalStateException("상품이동이 반영되지 않은 도착 검수입니다: 이동 "
                    + event.transferId() + ", 상품 " + item.productId());
            }
            int lost = quantity - item.goodQuantity() - item.defectiveQuantity();
            if (lost > 0) {
                losses.add(new StockChange(event.toLocationCode(), item.productId(), StockState.IN_TRANSIT, -lost));
            }
        });
        return losses;
    }

    private StockMovementCommand toMovement(TransferDispatched event) {
        List<StockChange> changes = new ArrayList<>();
        event.items().forEach(item -> {
            changes.add(new StockChange(
                event.fromLocationCode(), item.productId(), StockState.RESERVED, -item.quantity()));
            changes.add(new StockChange(
                event.toLocationCode(), item.productId(), StockState.IN_TRANSIT, item.quantity()));
        });
        return new StockMovementCommand(MovementType.DISPATCH, REF_TYPE, String.valueOf(event.transferId()),
            null, "wms", changes);
    }
}
