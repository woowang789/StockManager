package com.stockmanager.wms.application;

import com.stockmanager.common.event.OrderCancelRequested;
import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.common.messaging.ProcessedEventRepository;
import com.stockmanager.wms.domain.Shipment;
import com.stockmanager.wms.domain.ShipmentLine;
import com.stockmanager.wms.domain.ShipmentStatus;
import com.stockmanager.wms.infrastructure.ShipmentEventRecorder;
import com.stockmanager.wms.infrastructure.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Service
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipmentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ShipmentEventRecorder shipmentEventRecorder;
    private final TransactionTemplate transactionTemplate;

    ShipmentService(ShipmentRepository shipmentRepository, ProcessedEventRepository processedEventRepository,
                    ShipmentEventRecorder shipmentEventRecorder, TransactionTemplate transactionTemplate) {
        this.shipmentRepository = shipmentRepository;
        this.processedEventRepository = processedEventRepository;
        this.shipmentEventRecorder = shipmentEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public void createFrom(OrderReserved event) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.insert(event.eventId());
                shipmentRepository.insert(event.orderNo(), event.locationCode(), toLines(event));
            });
        } catch (DuplicateKeyException exception) {
            log.info("이미 처리한 이벤트입니다: {} (주문 {})", event.eventId(), event.orderNo());
        }
    }

    public void cancel(OrderCancelRequested event) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.insert(event.eventId());
                Shipment shipment = shipmentRepository.find(event.orderNo())
                    .orElseThrow(() -> new IllegalArgumentException("출하 작업이 없습니다: " + event.orderNo()));
                if (shipment.status() == ShipmentStatus.SHIPPED) {
                    log.info("이미 운송해서 취소하지 않습니다: {}", event.orderNo());
                    return;
                }
                boolean picked = shipment.status() != ShipmentStatus.READY;
                cancelOrFail(event.orderNo(), shipment.status());
                shipmentEventRecorder.shipmentCanceled(
                    event.orderNo(), shipment.locationCode(), shipment.lines(), List.of(), picked);
            });
        } catch (DuplicateKeyException exception) {
            log.info("이미 처리된 이벤트입니다: {} (주문 {})", event.eventId(), event.orderNo());
        }
    }

    public Shipment cancelForShortage(String orderNo, List<ShipmentLine> shortages) {
        return transactionTemplate.execute(status -> {
            Shipment shipment = shipmentRepository.find(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("출하 작업이 없습니다: " + orderNo));
            if (shipment.status() == ShipmentStatus.SHIPPED || shipment.status() == ShipmentStatus.CANCELED) {
                throw new IllegalArgumentException(
                    "취소할 수 없는 상태입니다: " + orderNo + " (" + shipment.status() + ")");
            }
            boolean picked = shipment.status() != ShipmentStatus.READY;
            cancelOrFail(orderNo, shipment.status());
            shipmentEventRecorder.shipmentCanceled(
                orderNo, shipment.locationCode(), shipment.lines(), shortages, picked);
            return shipmentRepository.find(orderNo).orElseThrow();
        });

    }

    private void cancelOrFail(String orderNo, ShipmentStatus from) {
        if (shipmentRepository.changeStatus(orderNo, from, ShipmentStatus.CANCELED) == 0) {
            throw new IllegalStateException("취소하는 사이에 상태가 바뀌었습니다: " + orderNo + " (" + from + ")");
        }
    }

    public Shipment pick(String orderNo){
        return advance(orderNo, ShipmentStatus.READY, ShipmentStatus.PICKED);
    }

    public Shipment pack(String orderNo) {
        return advance(orderNo, ShipmentStatus.PICKED, ShipmentStatus.PACKED);
    }

    private Shipment advance(String orderNo, ShipmentStatus from, ShipmentStatus to) {
        return transactionTemplate.execute(status -> {
            int changed = shipmentRepository.changeStatus(orderNo, from, to);
            Shipment shipment = shipmentRepository.find(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("출하 작업이 없습니다: " + orderNo));
            if (changed == 0 && shipment.status() != to) {
                throw new IllegalArgumentException(
                    to + "로 보낼 수 없는 상태입니다: " + orderNo + " (" + shipment.status() + ")");
            }
            return shipment;
        });
    }

    public Shipment ship(String orderNo) {
        Shipment shipped = transactionTemplate.execute(status -> {
            int changed = shipmentRepository.changeStatus(orderNo, ShipmentStatus.PACKED, ShipmentStatus.SHIPPED);
            Shipment shipment = shipmentRepository.find(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("출하 작업이 없습니다: " + orderNo));
            if (changed == 1) {
                shipmentEventRecorder.shipmentShipped(orderNo, shipment.locationCode(), shipment.lines());
            } else if (shipment.status() != ShipmentStatus.SHIPPED) {
                throw new IllegalArgumentException(
                    "운송할 수 없는 상태입니다: " + orderNo + " (" + shipment.status() + ")"
                );
            }
            return shipment;
        });
        return shipped;
    }

    public Shipment find(String orderNo) {
        return shipmentRepository.find(orderNo)
            .orElseThrow(() -> new IllegalArgumentException("출하 작업이 없습니다: " + orderNo));
    }

    private List<ShipmentLine> toLines(OrderReserved event) {
        return event.items().stream()
            .map(item -> new ShipmentLine(item.productId(), item.quantity()))
            .toList();
    }
}
