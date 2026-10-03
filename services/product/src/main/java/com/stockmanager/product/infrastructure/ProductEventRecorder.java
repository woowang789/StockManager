package com.stockmanager.product.infrastructure;

import com.stockmanager.common.event.ProductRegistered;
import com.stockmanager.common.messaging.OutboxRepository;
import com.stockmanager.product.domain.Product;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class ProductEventRecorder {

    static final String TOPIC = "product";

    private final OutboxRepository outboxRepository;

    ProductEventRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void productRegistered(Product product) {
        ProductRegistered event = new ProductRegistered(
            UUID.randomUUID().toString(), product.getId(), product.getSku(), product.getName(), Instant.now());
        outboxRepository.append(TOPIC, String.valueOf(product.getId()), event);
    }
}
