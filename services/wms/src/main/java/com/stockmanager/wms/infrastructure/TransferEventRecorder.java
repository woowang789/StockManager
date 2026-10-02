package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.TransferCanceled;
import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.common.event.TransferReceived;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
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

    public void transferReceived(Transfer transfer, List<InspectionLine> results, boolean putawayPending) {
        List<TransferReceived.Item> items = results.stream()
            .map(result -> new TransferReceived.Item(
                result.productId(), result.goodQuantity(), result.defectiveQuantity()))
            .toList();
        TransferReceived event = new TransferReceived(UUID.randomUUID().toString(), transfer.id(),
            transfer.toLocationCode(), items, putawayPending, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(transfer.id()), event);
    }

    public void transferCanceled(Transfer transfer, List<TransferLine> shortages, boolean putawayPending) {
        TransferCanceled event = new TransferCanceled(UUID.randomUUID().toString(), transfer.id(),
            transfer.fromLocationCode(), toItems(transfer.lines()), toItems(shortages), putawayPending, Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(transfer.id()), event);
    }

    private List<TransferCanceled.Item> toItems(List<TransferLine> lines) {
        return lines.stream()
            .map(line -> new TransferCanceled.Item(line.productId(), line.quantity()))
            .toList();
    }
}
