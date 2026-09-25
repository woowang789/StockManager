package com.stockmanager.wms.application;

import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.common.messaging.ProcessedEventRepository;
import com.stockmanager.wms.domain.ShipmentLine;
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
    private final TransactionTemplate transactionTemplate;

    ShipmentService(ShipmentRepository shipmentRepository, ProcessedEventRepository processedEventRepository, TransactionTemplate transactionTemplate) {
        this.shipmentRepository = shipmentRepository;
        this.processedEventRepository = processedEventRepository;
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

    private List<ShipmentLine> toLines(OrderReserved event) {
        return event.items().stream()
            .map(item -> new ShipmentLine(item.productId(), item.quantity()))
            .toList();
    }
}
