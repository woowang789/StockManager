package com.stockmanager.wms.application;

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

    public Shipment ship(String orderNo) {
        Shipment shipped = transactionTemplate.execute(status -> {
            int changed = shipmentRepository.changeStatus(orderNo, ShipmentStatus.READY, ShipmentStatus.SHIPPED);
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
