package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.event.InboundStored;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundOrigin;
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

    public void inboundInspected(long inboundId,String locationCode, List<InspectionLine> results,
                                 boolean putawayPending) {
        List<InboundInspected.Item> items = results.stream()
            .map(result -> new InboundInspected.Item(
                result.productId(), result.goodQuantity(), result.defectiveQuantity()))
            .toList();
        InboundInspected event = new InboundInspected(
            UUID.randomUUID().toString(), inboundId, locationCode, items, putawayPending, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(inboundId), event);
    }

    public void inboundStored(Inbound inbound, List<PutawayLine> lines) {
        List<InboundStored.Item> items = lines.stream()
            .map(line -> new InboundStored.Item(line.productId(), line.quantity()))
            .toList();
        InboundStored event = new InboundStored(
            UUID.randomUUID().toString(), inbound.id(), inbound.locationCode(), items, Instant.now());
        switch (inbound.origin()) {
            case InboundOrigin.Supplier() -> outboxRepository.append(TOPIC, String.valueOf(inbound.id()), event);
            case InboundOrigin.Transfer(long transferId) ->
                outboxRepository.append(TransferEventRecorder.TOPIC, String.valueOf(transferId), event);
        }
    }
}
