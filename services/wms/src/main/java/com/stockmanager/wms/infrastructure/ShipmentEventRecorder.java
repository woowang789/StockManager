package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.ShipmentCanceled;
import com.stockmanager.common.event.ShipmentShipped;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.wms.domain.ShipmentLine;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class ShipmentEventRecorder {

    static final String TOPIC = "wms.shipment";

    private final OutboxRepository outboxRepository;

    ShipmentEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void shipmentShipped(String orderNo, String locationCode, List<ShipmentLine> lines) {
        List<ShipmentShipped.Item> items = lines.stream()
            .map(line -> new ShipmentShipped.Item(line.productId(), line.quantity()))
            .toList();
        ShipmentShipped event = new ShipmentShipped(
            UUID.randomUUID().toString(), orderNo, locationCode, items, Instant.now());
        outboxRepository.append(TOPIC, orderNo, event);
    }

    public void shipmentCanceled(String orderNo, String locationCode, List<ShipmentLine> lines, boolean picked) {
        List<ShipmentCanceled.Item> items = lines.stream()
            .map(line -> new ShipmentCanceled.Item(line.productId(), line.quantity()))
            .toList();
        ShipmentCanceled event = new ShipmentCanceled(
            UUID.randomUUID().toString(), orderNo, locationCode, items, picked, Instant.now());
        outboxRepository.append(TOPIC, orderNo, event);
    }

}
