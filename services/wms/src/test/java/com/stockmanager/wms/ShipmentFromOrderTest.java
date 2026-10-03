package com.stockmanager.wms;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmanager.common.event.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentFromOrderTest {

    private static final String TOPIC = "sales.order";

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearShipments() {
        jdbcClient.sql("DELETE FROM reputaway_item").update();
        jdbcClient.sql("DELETE FROM reputaway").update();
        jdbcClient.sql("DELETE FROM shipment_item").update();
        jdbcClient.sql("DELETE FROM shipment").update();
        jdbcClient.sql("DELETE FROM processed_event").update();
    }

    @Test
    @DisplayName("예약된 주문을 받으면 출하 작업이 생긴다")
    void createsShipmentFromOrderReserved() {
        publish("OrderReserved", "evt-1", "ORD-1");

        assertThat(waitForShipment("ORD-1")).isEqualTo("READY");
        assertThat(itemsOf("ORD-1")).containsExactly("1:3");
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 출하 작업은 하나다")
    void createsShipmentOnceForRepeatedEvent() {
        publish("OrderReserved", "evt-2","ORD-2");
        publish("OrderReserved", "evt-2","ORD-2");

        assertThat(waitForShipment("ORD-2")).isEqualTo("READY");
        sleep(Duration.ofSeconds(2));
        assertThat(countOf("ORD-2")).isEqualTo(1);
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("아직 다루지 않는 이벤트는 건너뛴다")
    void skipsUnknownEventType() {
        publish("OrderCancelRequested", "evt-3", "ORD-3");

        sleep(Duration.ofSeconds(3));
        assertThat(countOf("ORD-3")).isZero();
    }


    private void publish(String eventType, String eventId, String orderNo) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC","items":[{"productId":1,"quantity":3}],
                 "occurredAt":"2026-09-24T00:00:00Z"}
                """.formatted(eventId, orderNo);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, orderNo, payload,
            List.of(new RecordHeader(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private String waitForShipment(String orderNo) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbcClient.sql("SELECT status FROM shipment WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(String.class)
                .optional()
                .orElse(null);
            if (status != null) {
                return status;
            }
            sleep(Duration.ofMillis(200));
        }
        return null;
    }

    private int processedEventCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM processed_event").query(Integer.class).single();
    }

    private int countOf(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM shipment WHERE order_no = :orderNo")
            .param("orderNo", orderNo)
            .query(Integer.class)
            .single();
    }

    private List<String> itemsOf(String orderNo) {
        return jdbcClient.sql("""
                        SELECT CONCAT(i.product_id, ':', i.quantity)
                        FROM shipment_item i JOIN shipment s ON s.id = i.shipment_id
                        WHERE s.order_no = :orderNo ORDER BY i.product_id
                        """)
            .param("orderNo", orderNo)
            .query(String.class)
            .list();
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
