package com.stockmanager.inventory.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.inventory.application.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class TransferEventListener {

    private static final String TRANSFER_DISPATCHED = TransferDispatched.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    TransferEventListener(TransferService transferService, ObjectMapper objectMapper) {
        this.transferService = transferService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "wms.transfer")
    void onTransferEvent(@Header(EventHeaders.EVENT_TYPE) String eventType, @Payload String payload) {
        if (TRANSFER_DISPATCHED.equals(eventType)) {
            transferService.dispatch(objectMapper.readValue(payload, TransferDispatched.class));
            return;
        }
        log.debug("아직 처리하지 않는 이벤트입니다: {}", eventType);
    }
}
