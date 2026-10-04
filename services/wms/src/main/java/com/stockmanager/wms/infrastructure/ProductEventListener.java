package com.stockmanager.wms.infrastructure;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.ProductRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ProductEventListener {

    private static final String PRODUCT_REGISTERED = ProductRegistered.class.getSimpleName();

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    ProductEventListener(ProductRepository productRepository, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "product")
    void onProductEvent(@Header(EventHeaders.EVENT_TYPE) String eventType, @Payload String payload) {
        if (PRODUCT_REGISTERED.equals(eventType)) {
            ProductRegistered event = objectMapper.readValue(payload, ProductRegistered.class);
            productRepository.save(event.productId(), event.sku(), event.name());
            return;
        }
        log.debug("아직 처리하지 않는 이벤트입니다: {}", eventType);
    }
}
