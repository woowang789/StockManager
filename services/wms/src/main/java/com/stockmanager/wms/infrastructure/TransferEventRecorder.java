package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.Transfer;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class TransferEventRecorder {

    static final String TOPIC = "wms.transfer";

    private final OutboxRepository outboxRepository;

    TransferEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void transferDispatched(Transfer transfer) {
        List<TransferDispatched.Item> items = transfer.lines().stream()
            .map(line -> new TransferDispatched.Item(line.productId(), line.quantity()))
            .toList();
        TransferDispatched event = new TransferDispatched(UUID.randomUUID().toString(), transfer.id(),
            transfer.fromLocationCode(), transfer.toLocationCode(), items, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(transfer.id()), event);
    }
}
