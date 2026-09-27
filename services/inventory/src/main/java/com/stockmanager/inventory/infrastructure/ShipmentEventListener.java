package com.stockmanager.inventory.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.ShipmentCanceled;
import com.stockmanager.common.event.ShipmentShipped;
import com.stockmanager.inventory.application.ShipmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ShipmentEventListener {

    private static final String SHIPMENT_SHIPPED = ShipmentShipped.class.getSimpleName();
    private static final String SHIPMENT_CANCELED = ShipmentCanceled.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventListener.class);

    private final ShipmentService shipmentService;
    private final ObjectMapper objectMapper;

    ShipmentEventListener(ShipmentService shipmentService, ObjectMapper objectMapper) {
        this.shipmentService = shipmentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "wms.shipment")
    void onShipmentEvent(@Header(EventHeaders.EVENT_TYPE) String eventType, @Payload String payload) {
        if (SHIPMENT_SHIPPED.equals(eventType)) {
            shipmentService.apply(objectMapper.readValue(payload, ShipmentShipped.class));
            return;
        }
        if (SHIPMENT_CANCELED.equals(eventType)) {
            shipmentService.release(objectMapper.readValue(payload, ShipmentCanceled.class));
            return;
        }
        log.debug("아직 처리하지 않는 이벤트입니다: {}", eventType);
    }
}
