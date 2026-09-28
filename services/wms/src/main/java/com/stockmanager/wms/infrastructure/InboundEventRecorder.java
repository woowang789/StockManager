package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.InspectionLine;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class InboundEventRecorder {

    static final String TOPIC = "wms.inbound";

    private final OutboxRepository outboxRepository;

    InboundEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void inboundInspected(long inboundId, String locationCode, List<InspectionLine> results) {
        List<InboundInspected.Item> items = results.stream()
            .map(result -> new InboundInspected.Item(
                result.productId(), result.goodQuantity(), result.defectiveQuantity()))
            .toList();
        InboundInspected event = new InboundInspected(
            UUID.randomUUID().toString(), inboundId, locationCode, items, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(inboundId), event);
    }
}
