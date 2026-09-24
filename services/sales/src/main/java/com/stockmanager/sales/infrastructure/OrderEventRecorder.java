package com.stockmanager.sales.infrastructure;

import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.sales.domain.SalesOrder;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderEventRecorder {

    static final String TOPIC = "sales.order";

    private final OutboxRepository outboxRepository;

    OrderEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void orderReserved(SalesOrder order) {
        List<OrderReserved.Item> items = order.getLines().stream()
            .map(line -> new OrderReserved.Item(line.productId(), line.quantity()))
            .toList();
        OrderReserved event = new OrderReserved(
            UUID.randomUUID().toString(), order.getOrderNo(), order.getLocationCode(), items, Instant.now());
        outboxRepository.append(TOPIC, order.getOrderNo(), event);
    }
}
