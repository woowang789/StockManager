package com.stockmanager.wms;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.wms.infrastructure.ProductRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProductReplicaTest {

    private static final String TOPIC = "product";

    @Autowired
    ProductRepository productRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearProducts() {
        jdbcClient.sql("DELETE FROM product").update();
    }

    @Test
    @DisplayName("ProductRegistered를 받으면 상품 정보를 복제해 둔다")
    void replicatesRegisteredProduct() {
        publish("ProductRegistered", 101, "SKU-101", "무선 마우스");

        assertThat(waitForProduct(101, "SKU-101 무선 마우스")).isEqualTo("SKU-101 무선 마우스");
    }

    @Test
    @DisplayName("ProductUpdated를 받으면 복제본을 고친다")
    void appliesUpdatedProduct() {
        // 같은 상품은 같은 키라 같은 파티션으로 간다. 등록보다 수정이 먼저 적히지 않는다
        publish("ProductRegistered", 103, "SKU-103", "모니터");
        publish("ProductUpdated", 103, "SKU-103", "27인치 모니터");

        assertThat(waitForProduct(103, "SKU-103 27인치 모니터")).isEqualTo("SKU-103 27인치 모니터");
    }

    @Test
    @DisplayName("이미 있는 상품을 다시 받으면 받은 내용으로 덮고 한 줄로 둔다")
    void overwritesWithReceivedProduct() {
        productRepository.save(102, "SKU-102", "키보드");
        productRepository.save(102, "SKU-102", "기계식 키보드");

        assertThat(productOf(102)).isEqualTo("SKU-102 기계식 키보드");
        assertThat(productCount()).isEqualTo(1);
    }

    private void publish(String eventType, long productId, String sku, String name) {
        String payload = """
                {"eventId":"%s-%d","productId":%d,"sku":"%s","name":"%s","occurredAt":"2026-10-04T00:00:00Z"}
                """.formatted(eventType, productId, productId, sku, name);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(productId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    // 기대한 모습이 될 때까지 기다린다. 시간이 다 되면 마지막에 본 모습을 돌려준다
    private String waitForProduct(long productId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String product = productOf(productId);
        while (!expected.equals(product) && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(200));
            product = productOf(productId);
        }
        return product;
    }

    private String productOf(long productId) {
        return jdbcClient.sql("SELECT CONCAT(sku, ' ', name) FROM product WHERE id = :productId")
            .param("productId", productId)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private int productCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM product").query(Integer.class).single();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
