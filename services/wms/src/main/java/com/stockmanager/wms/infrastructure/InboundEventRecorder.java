package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.event.InboundStored;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.PutawayLine;
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

    public void inboundStored(long inboundId, String locationCode, List<PutawayLine> lines) {
        List<InboundStored.Item> items = lines.stream()
            .map(line -> new InboundStored.Item(line.productId(), line.quantity()))
            .toList();
        InboundStored event = new InboundStored(
            UUID.randomUUID().toString(), inboundId, locationCode, items, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(inboundId), event);
    }
}
