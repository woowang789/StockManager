package com.stockmanager.sales.infrastructure;

import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.sales.domain.SalesOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderEventPublisher {

    static final String TOPIC = "sales.order";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void orderReserved(SalesOrder order) {
        List<OrderReserved.Item> items = order.getLines().stream()
            .map(line -> new OrderReserved.Item(line.productId(), line.quantity()))
            .toList();

        OrderReserved event = new OrderReserved(UUID.randomUUID().toString(), order.getOrderNo(), order.getLocationCode(), items, Instant.now());
        kafkaTemplate.send(TOPIC, order.getOrderNo(), event);
    }
}
