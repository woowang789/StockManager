package com.stockmanager.inventory;

import com.stockmanager.common.event.EventHeaders;
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
class InboundStoreTest {

    private static final String TOPIC = "wms.inbound";

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("적치하면 적치대기에서 가용으로 옮겨진다")
    void movesToAvailable() {
        given(911, 9, 1);
        publishStored(911, 9);

        assertThat(waitForMovement(911)).isEqualTo(1);
        assertThat(quantityOf("putaway_wait")).isZero();
        assertThat(quantityOf("available")).isEqualTo(9);
        assertThat(quantityOf("defective")).isEqualTo(1);
    }

    @Test
    @DisplayName("적치 전표는 줄 합계가 0이다")
    void putawayIsAnInternalMove() {
        given(912, 5, 0);
        publishStored(912, 5);

        assertThat(waitForMovement(912)).isEqualTo(1);
        assertThat(entrySumOf(912)).isZero();
        assertThat(entryCountOf(912)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 적치가 두 번 와도 한 번만 옮겨진다")
    void storesOnce() {
        given(913, 4, 0);

        publishStored(913, 4);
        publishStored(913, 4);

        assertThat(waitForMovement(913)).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount(913)).isEqualTo(1);
        assertThat(quantityOf("available")).isEqualTo(4);
    }

    // 적치 전 상태를 만든다: 검수까지 끝나 적치대기에 있는 재고
    private void given(long inboundId, int good, int defective) {
        publish(inboundId, "InboundInspected", """
                {"eventId":"insp-%d","inboundId":%d,"locationCode":"DC",
                 "items":[{"productId":1,"goodQuantity":%d,"defectiveQuantity":%d}],
                 "putawayPending":true,"occurredAt":"2026-09-28T00:00:00Z"}
                """.formatted(inboundId, inboundId, good, defective));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && quantityOf("putaway_wait") != good) {
            sleep(Duration.ofMillis(200));
        }
    }

    private void publishStored(long inboundId, int quantity) {
        publish(inboundId, "InboundStored", """
                {"eventId":"str-%d-%d","inboundId":%d,"locationCode":"DC",
                 "items":[{"productId":1,"quantity":%d}],
                 "occurredAt":"2026-09-28T00:00:00Z"}
                """.formatted(inboundId, quantity, inboundId, quantity));
    }

    private void publish(long inboundId, String eventType, String payload) {
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(inboundId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForMovement(long inboundId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = movementCount(inboundId);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int movementCount(long inboundId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type='PUTAWAY' AND ref_id = :refId")
            .param("refId", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private int entrySumOf(long inboundId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(e.delta), 0)
                        FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = 'PUTAWAY' AND m.ref_id = :refId
                        """)
            .param("refId", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private int entryCountOf(long inboundId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = 'PUTAWAY' AND m.ref_id = :refId
                        """)
            .param("refId", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private int quantityOf(String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code='DC' AND product_id=1".formatted(column))
            .query(Integer.class)
            .optional()
            .orElse(0);
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
