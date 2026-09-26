package com.stockmanager.sales.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.ShipmentShipped;
import com.stockmanager.sales.application.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ShipmentEventListener {

    private static final String SHIPMENT_SHIPPED = ShipmentShipped.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventListener.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    ShipmentEventListener(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "wms.shipment")
    void onShipmentEvent(@Header(EventHeaders.EVENT_TYPE) String eventType, @Payload String payload) {
        if (!SHIPMENT_SHIPPED.equals(eventType)) {
            log.debug("아직 처리하지 않는 이벤트입니다: {}", eventType);
            return;
        }
        ShipmentShipped event = objectMapper.readValue(payload, ShipmentShipped.class);
        orderService.markShipped(event.orderNo());
    }
}
