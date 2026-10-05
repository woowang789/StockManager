package com.stockmanager.inventory.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.event.InboundStored;
import com.stockmanager.inventory.application.InboundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class InboundEventListener {

    private static final String INBOUND_INSPECTED = InboundInspected.class.getSimpleName();

    private static final String INBOUND_STORED = InboundStored.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(InboundEventListener.class);

    private final InboundService inboundService;
    private final ObjectMapper objectMapper;

    InboundEventListener(InboundService inboundService, ObjectMapper objectMapper) {
        this.inboundService = inboundService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "wms.inbound")
    void onInboundEvent(@Header(EventHeaders.EVENT_TYPE) String eventType,
                        @Header(value = EventHeaders.USER_ID, defaultValue = "wms") String actor, @Payload String payload) {
        if (INBOUND_INSPECTED.equals(eventType)) {
            inboundService.apply(objectMapper.readValue(payload, InboundInspected.class), actor);
            return;
        }
        if(INBOUND_STORED.equals(eventType)){
            inboundService.store(objectMapper.readValue(payload, InboundStored.class), actor);
            return;
        }
        log.debug("아직 처리하지 않는 이벤트입니다: {}",eventType);
    }
}

