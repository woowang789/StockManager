package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.common.messaging.OutboxPublisher;
import com.stockmanager.wms.application.ShipmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class OrderEventListener {

    private static final String ORDER_RESERVED = OrderReserved.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final ShipmentService shipmentService;
    private final ObjectMapper objectMapper;

    OrderEventListener(ShipmentService shipmentService, ObjectMapper objectMapper) {
        this.shipmentService = shipmentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "sales.order")
    void onOrderEvent(@Header(OutboxPublisher.EVENT_TYPE_HEADER) String eventType, @Payload String payload) {
        if (!ORDER_RESERVED.equals(eventType)) {
            log.debug("아직 처리하지 않는 이벤트입니다: {}",eventType);
            return;
        }
        shipmentService.createFrom(objectMapper.readValue(payload, OrderReserved.class));
    }
}
