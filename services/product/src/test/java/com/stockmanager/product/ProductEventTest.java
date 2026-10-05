package com.stockmanager.product;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.ProductRegistered;
import com.stockmanager.common.event.ProductUpdated;
import com.stockmanager.product.application.ProductService;
import com.stockmanager.product.domain.Product;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProductEventTest {

    private static final String TOPIC = "product";

    @Autowired
    ProductService productService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    ObjectMapper objectMapper;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void clearAndSubscribe() {
        jdbcClient.sql("DELETE FROM product").update();

        // 테스트마다 새 그룹으로 토픽을 처음부터 읽는다. 이 테스트가 등록한 상품 번호만 골라서 본다
        consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    @DisplayName("상품을 등록하면 ProductRegistered가 상품 번호를 키로 발행된다")
    void publishesProductRegistered() {
        Product product = productService.register("EVT-001", "무선 마우스");

        ConsumerRecord<String, String> record = waitForRecords(String.valueOf(product.getId()), 1).getFirst();
        assertThat(eventTypeOf(record)).isEqualTo("ProductRegistered");

        ProductRegistered event = objectMapper.readValue(record.value(), ProductRegistered.class);
        assertThat(event.productId()).isEqualTo(product.getId());
        assertThat(event.sku()).isEqualTo("EVT-001");
        assertThat(event.name()).isEqualTo("무선 마우스");
        assertThat(event.eventId()).isNotBlank();
    }

    @Test
    @DisplayName("고치면 ProductUpdated가 등록과 같은 키로 그다음에 발행된다")
    void publishesProductUpdatedAfterRegistered() {
        Product product = productService.register("EVT-003", "모니터");
        productService.change(product.getId(), 0, "EVT-003", "27인치 모니터");

        List<ConsumerRecord<String, String>> records = waitForRecords(String.valueOf(product.getId()), 2);
        assertThat(records).extracting(this::eventTypeOf).containsExactly("ProductRegistered", "ProductUpdated");

        ProductUpdated event = objectMapper.readValue(records.get(1).value(), ProductUpdated.class);
        assertThat(event.name()).isEqualTo("27인치 모니터");
    }

    @Test
    @DisplayName("고쳤는데 바뀐 것이 없으면 version도 그대로고 알리지 않는다")
    void publishesNothingWhenNothingChanged() {
        Product product = productService.register("EVT-004", "마우스 패드");

        Product unchanged = productService.change(product.getId(), 0, "EVT-004", "마우스 패드").orElseThrow();

        assertThat(unchanged.getVersion()).isZero();
        assertThat(updatedEventCount(product.getId())).isZero();
    }

    @Test
    @DisplayName("알림을 적지 못하면 상품도 남지 않는다")
    void savesNothingWhenEventIsNotRecorded() {
        // outbox에 쓰지 못하는 상황을 일부러 만든다. 상품 INSERT는 이미 끝난 뒤에 실패한다
        jdbcClient.sql("RENAME TABLE outbox TO outbox_unavailable").update();
        try {
            assertThatThrownBy(() -> productService.register("EVT-002", "키보드"))
                .isInstanceOf(BadSqlGrammarException.class);
        } finally {
            jdbcClient.sql("RENAME TABLE outbox_unavailable TO outbox").update();
        }

        assertThat(productCountOf("EVT-002")).isZero();
    }

    // 같은 키의 레코드를 받은 순서대로 모은다. 같은 파티션이라 받은 순서가 곧 발행 순서다
    private List<ConsumerRecord<String, String>> waitForRecords(String productId, int count) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (found.size() < count && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (productId.equals(record.key())) {
                    found.add(record);
                }
            }
        }
        if (found.size() < count) {
            throw new AssertionError("발행되지 않았습니다: 상품 " + productId + ", " + found.size() + "건");
        }
        return found;
    }

    private String eventTypeOf(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader(EventHeaders.EVENT_TYPE).value(), StandardCharsets.UTF_8);
    }

    private int updatedEventCount(long productId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'ProductUpdated' AND message_key = :key")
            .param("key", String.valueOf(productId))
            .query(Integer.class)
            .single();
    }

    private int productCountOf(String sku) {
        return jdbcClient.sql("SELECT COUNT(*) FROM product WHERE sku = :sku")
            .param("sku", sku)
            .query(Integer.class)
            .single();
    }

}
