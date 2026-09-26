package com.stockmanager.wms;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.ShipmentCanceled;
import com.stockmanager.wms.application.ShipmentService;
import com.stockmanager.wms.domain.ShipmentLine;
import com.stockmanager.wms.infrastructure.ShipmentRepository;
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
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
public class ShipmentCancelTest {

    private static final String TOPIC = "sales.order";

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM shipment_item").update();
        jdbcClient.sql("DELETE FROM shipment").update();
        jdbcClient.sql("DELETE FROM processed_event").update();
        jdbcClient.sql("DELETE FROM outbox").update();
    }

    @Test
    @DisplayName("피킹 전에 취소하면 꺼내지 않았다고 알린다")
    void cancelsBeforePicking() {
        shipmentRepository.insert("ORD-1", "DC", List.of(new ShipmentLine(1, 3)));

        publishCancel("evt-1", "ORD-1");

        assertThat(waitForStatus("ORD-1", "CANCELED")).isTrue();
        ShipmentCanceled event = canceledEventOf("ORD-1");
        assertThat(event.picked()).isFalse();
        assertThat(event.items()).containsExactly(new ShipmentCanceled.Item(1, 3));
    }

    @Test
    @DisplayName("피킹한 뒤에 취소하면 이미 꺼냈다고 알린다")
    void cancelAfterPicking() {
        shipmentRepository.insert("ORD-2", "DC", List.of(new ShipmentLine(1, 3)));
        shipmentService.pick("ORD-2");

        publishCancel("evt-2", "ORD-2");

        assertThat(waitForStatus("ORD-2", "CANCELED")).isTrue();
        assertThat(canceledEventOf("ORD-2").picked()).isTrue();
    }

    @Test
    @DisplayName("이미 운송했으면 취소하지 않는다")
    void ignoresCancelAfterShipping() {
        shipmentRepository.insert("ORD-3", "DC", List.of(new ShipmentLine(1, 3)));
        shipmentService.pick("ORD-3");
        shipmentService.pack("ORD-3");
        shipmentService.ship("ORD-3");

        publishCancel("evt-3", "ORD-3");

        sleep(Duration.ofSeconds(3));
        assertThat(statusOf("ORD-3")).isEqualTo("SHIPPED");
        assertThat(canceledPayloadOf("ORD-3")).isEmpty();
    }

    private void publishCancel(String eventId, String orderNo) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","occurredAt":"2026-09-26T00:00:00Z"}
                """.formatted(eventId, orderNo);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, orderNo, payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "OrderCancelRequested".getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private ShipmentCanceled canceledEventOf(String orderNo) {
        return objectMapper.readValue(canceledPayloadOf(orderNo).orElseThrow(), ShipmentCanceled.class);
    }

    private Optional<String> canceledPayloadOf(String orderNo) {
        return jdbcClient.sql("""
                        SELECT payload FROM outbox
                        WHERE message_key = :orderNo AND event_type = 'ShipmentCanceled'
                        """)
            .param("orderNo", orderNo)
            .query(String.class)
            .optional();
    }

    private boolean waitForStatus(String orderNo, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(statusOf(orderNo))) {
                return true;
            }
            sleep(Duration.ofMillis(200));
        }
        return false;
    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM shipment WHERE order_no = :orderNo")
            .param("orderNo", orderNo)
            .query(String.class)
            .single();
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
