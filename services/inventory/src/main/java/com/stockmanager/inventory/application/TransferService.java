package com.stockmanager.inventory.application;

import com.stockmanager.common.event.TransferDispatched;
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
