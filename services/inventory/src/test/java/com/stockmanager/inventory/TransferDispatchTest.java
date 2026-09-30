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
class TransferDispatchTest {

    private static final String TOPIC = "wms.transfer";

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
        jdbcClient.sql("DELETE FROM reservation").update();
    }

    @Test
    @DisplayName("상품이동하면 출발 거점의 예약이 빠지고 도착 거점에 이동중이 생긴다")
    void movesBetweenLocations() {
        given(901, 3);

        publishDispatched(901, 3);

        assertThat(waitForMovement(901)).isEqualTo(1);
        assertThat(quantityOf("DC", "reserved")).isZero();
        assertThat(quantityOf("STORE", "in_transit")).isEqualTo(3);

        assertThat(quantityOf("STORE", "available")).isZero();
    }

    @Test
    @DisplayName("전표 한 장이 두 거점을 건드리고 줄 합계는 0이다")
    void oneMovementTouchesTwoLocations() {
        given(902, 5);

        publishDispatched(902, 5);

        assertThat(waitForMovement(902)).isEqualTo(1);

        assertThat(entrySumOf(902)).isZero();
        assertThat(entryCountOf(902)).isEqualTo(2);
        assertThat(entryLocationsOf(902)).containsExactly("DC", "STORE");
    }

    @Test
    @DisplayName("이동에 쓰인 예약은 소비된다")
    void consumesReservation() {
        given(903, 2);

        publishDispatched(903, 2);

        assertThat(waitForMovement(903)).isEqualTo(1);
        assertThat(reservationStatusOf(903)).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("같은 이동이 두 번 와도 한 번만 반영된다")
    void dispatchesOnce() {
        given(904, 4);

        publishDispatched(904, 4);
        publishDispatched(904, 4);

        assertThat(waitForMovement(904)).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount(904)).isEqualTo(1);
        assertThat(quantityOf("STORE", "in_transit")).isEqualTo(4);
    }


    // 출발 거점에 예약으로 잡힌 재고와 그 예약 행을 만들어 둔다
    private void given(long transferId, int quantity) {
        jdbcClient.sql("""
                        INSERT INTO stock (location_code, product_id, reserved)
                        VALUES ('DC', 1, :quantity)
                        """)
            .param("quantity", quantity)
            .update();
        jdbcClient.sql("""
                        INSERT INTO reservation (ref_type, ref_id, location_code, product_id, quantity, status)
                        VALUES ('TRANSFER', :refId, 'DC', 1, :quantity, 'ACTIVE')
                        """)
            .param("refId", String.valueOf(transferId))
            .param("quantity", quantity)
            .update();
    }

    private void publishDispatched(long transferId, int quantity) {
        String payload = """
                {"eventId":"%s","transferId":%d,"fromLocationCode":"DC","toLocationCode":"STORE",
                 "items":[{"productId":1,"quantity":%d}],"occurredAt":"2026-09-29T00:00:00Z"}
                """.formatted("evt-" + transferId, transferId, quantity);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(transferId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "TransferDispatched".getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForMovement(long transferId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = movementCount(transferId);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int movementCount(long transferId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_movement
                        WHERE type = 'DISPATCH' AND ref_type = 'TRANSFER' AND ref_id = :refId
                        """)
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private int entrySumOf(long transferId) {
        return jdbcClient.sql(entryQuery("COALESCE(SUM(e.delta), 0)"))
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private int entryCountOf(long transferId) {
        return jdbcClient.sql(entryQuery("COUNT(*)"))
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private List<String> entryLocationsOf(long transferId) {
        return jdbcClient.sql(entryQuery("e.location_code") + " ORDER BY e.location_code")
            .param("refId", String.valueOf(transferId))
            .query(String.class)
            .list();
    }

    private String entryQuery(String select) {
        return """
                SELECT %s
                FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                WHERE m.type = 'DISPATCH' AND m.ref_type = 'TRANSFER' AND m.ref_id = :refId
                """.formatted(select);
    }

    private String reservationStatusOf(long transferId) {
        return jdbcClient.sql("SELECT status FROM reservation WHERE ref_type='TRANSFER' AND ref_id = :refId")
            .param("refId", String.valueOf(transferId))
            .query(String.class)
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
