package com.stockmanager.inventory.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.InboundStored;
import com.stockmanager.common.event.ReputawayStored;
import com.stockmanager.common.event.TransferCanceled;
import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.common.event.TransferReceived;
import com.stockmanager.inventory.application.InboundService;
import com.stockmanager.inventory.application.ReputawayService;
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
    private static final String TRANSFER_RECEIVED = TransferReceived.class.getSimpleName();
    private static final String TRANSFER_CANCELED = TransferCanceled.class.getSimpleName();
    private static final String INBOUND_STORED = InboundStored.class.getSimpleName();
    private static final String REPUTAWAY_STORED = ReputawayStored.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    private final TransferService transferService;
    private final InboundService inboundService;
    private final ReputawayService reputawayService;
    private final ObjectMapper objectMapper;

    TransferEventListener(TransferService transferService, InboundService inboundService,ReputawayService reputawayService,ObjectMapper objectMapper) {
        this.transferService = transferService;
        this.inboundService = inboundService;
        this.reputawayService = reputawayService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "wms.transfer")
    void onTransferEvent(@Header(EventHeaders.EVENT_TYPE) String eventType,
                         @Header(value = EventHeaders.USER_ID, defaultValue = "wms") String actor, @Payload String payload) {
        if (TRANSFER_DISPATCHED.equals(eventType)) {
            transferService.dispatch(objectMapper.readValue(payload, TransferDispatched.class),actor);
            return;
        }
        if (TRANSFER_RECEIVED.equals(eventType)) {
            transferService.receive(objectMapper.readValue(payload, TransferReceived.class),actor);
            return;
        }
        if (TRANSFER_CANCELED.equals(eventType)) {
            transferService.release(objectMapper.readValue(payload, TransferCanceled.class),actor);
            return;
        }
        if (INBOUND_STORED.equals(eventType)) {
            inboundService.store(objectMapper.readValue(payload, InboundStored.class),actor);
            return;
        }
        if (REPUTAWAY_STORED.equals(eventType)) {
            reputawayService.store(objectMapper.readValue(payload, ReputawayStored.class),actor);
            return;
        }
        log.debug("아직 처리하지 않는 이벤트입니다: {}", eventType);
    }
}
