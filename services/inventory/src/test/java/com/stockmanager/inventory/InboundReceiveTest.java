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
class InboundReceiveTest {

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
    @DisplayName("센터 검수는 양품을 적치대기로, 불량을 불량으로 올린다")
    void receivesAtCenter() {
        publishInspected(901, "DC", 9, 1);

        assertThat(waitForMovement(901)).isEqualTo(1);

        assertThat(quantityOf("DC", "putaway_wait")).isEqualTo(9);
        assertThat(quantityOf("DC", "defective")).isEqualTo(1);
        assertThat(quantityOf("DC", "available")).isZero();
    }

    @Test
    @DisplayName("매장 검수는 적치 없이 바로 가용으로 올린다")
    void receivesAtStore() {
        publishInspected(902, "STORE", 5, 0);

        assertThat(waitForMovement(902)).isEqualTo(1);
        assertThat(quantityOf("STORE", "putaway_wait")).isZero();
        assertThat(quantityOf("STORE", "available")).isEqualTo(5);
    }

    @Test
    @DisplayName("같은 입고가 두 번 와도 한 번만 올라간다")
    void receivesOnce() {
        publishInspected(903, "DC", 4, 0);
        publishInspected(903, "DC", 4, 0);

        assertThat(waitForMovement(903)).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount(903)).isEqualTo(1);
        assertThat(quantityOf("DC", "putaway_wait")).isEqualTo(4);
    }

    @Test
    @DisplayName("입고 전표는 줄 합계가 0이 아니다")
    void receiveIsNotAnInternalMove() {
        publishInspected(904, "DC", 9, 1);

        assertThat(waitForMovement(904)).isEqualTo(1);
        assertThat(entrySumOf(904)).isEqualTo(10);
    }

    private void publishInspected(long inboundId, String locationCode, int good, int defective) {
        String payload = """
                {"eventId":"evt-%d-%d","inboundId":%d,"locationCode":"%s",
                 "items":[{"productId":1,"goodQuantity":%d,"defectiveQuantity":%d}],
                 "occurredAt":"2026-09-27T00:00:00Z"}
                """.formatted(inboundId, good, inboundId, locationCode, good, defective);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(inboundId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "InboundInspected".getBytes(StandardCharsets.UTF_8)))))
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
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = 'RECEIVE' AND ref_id = :refId")
            .param("refId", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private int entrySumOf(long inboundId) {
        return jdbcClient.sql("""
                    SELECT COALESCE(SUM(e.delta),0)
                    FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                    WHERE m.type = 'RECEIVE' AND m.ref_id = :refId
                """)
            .param("refId", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private int quantityOf(String locationCode, String column) {
        return jdbcClient.sql("""
                        SELECT %s FROM stock WHERE location_code = :locationCode AND product_id = 1
                        """.formatted(column))
            .param("locationCode", locationCode)
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
