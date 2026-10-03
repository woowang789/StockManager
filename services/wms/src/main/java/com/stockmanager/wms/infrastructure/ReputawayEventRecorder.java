package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.ReputawayStored;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.PutawayLine;
import com.stockmanager.wms.domain.Reputaway;
import com.stockmanager.wms.domain.ReputawayOrigin;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class ReputawayEventRecorder {

    private final OutboxRepository outboxRepository;

    ReputawayEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void reputawayStored(Reputaway reputaway, List<PutawayLine> lines) {
        List<ReputawayStored.Item> items = lines.stream()
            .map(line -> new ReputawayStored.Item(line.productId(), line.quantity()))
            .toList();
        ReputawayStored event = new ReputawayStored(
            UUID.randomUUID().toString(), reputaway.id(), reputaway.locationCode(), items, Instant.now());

        switch (reputaway.origin()) {
            case ReputawayOrigin.Shipment(String orderNo) ->
                outboxRepository.append(ShipmentEventRecorder.TOPIC, orderNo, event);
            case ReputawayOrigin.Transfer(long transferId) ->
                outboxRepository.append(TransferEventRecorder.TOPIC, String.valueOf(transferId), event);
        }
    }
}
