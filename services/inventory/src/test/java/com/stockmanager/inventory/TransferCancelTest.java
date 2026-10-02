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
class TransferCancelTest {

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
    @DisplayName("피킹 전에 취소하면 예약이 가용으로 돌아가고 예약 기록도 풀린다")
    void releasesToAvailableBeforePick() {
        reserved(901, "DC", 3);

        publishCanceled(901, "DC", 3, false, "");

        assertThat(waitForMovement(901, "RELEASE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "reserved")).isZero();
        assertThat(quantityOf("DC", 1, "available")).isEqualTo(3);
        assertThat(reservationStatusOf(901)).isEqualTo("RELEASED");

        assertThat(entrySumOf(901, "RELEASE")).isZero();
    }

    @Test
    @DisplayName("센터에서 피킹한 뒤 취소하면 다시 적치하도록 적치대기로 돌아간다")
    void releasesToPutawayWaitAfterPickAtCenter() {
        reserved(902, "DC", 3);

        publishCanceled(902, "DC", 3, true, "");

        assertThat(waitForMovement(902, "RELEASE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "putaway_wait")).isEqualTo(3);
        assertThat(quantityOf("DC", 1, "available")).isZero();
    }

    @Test
    @DisplayName("결품은 되돌린 자리에서 조정으로 뺀다")
    void adjustsShortageWhereReleased() {
        reserved(904, "DC", 3);

        publishCanceled(904, "DC", 3, true, "{\"productId\":1,\"quantity\":1}");

        assertThat(waitForMovement(904, "ADJUST")).isEqualTo(1);
        assertThat(movementCount(904, "RELEASE")).isEqualTo(1);
        assertThat(reasonOf(904, "ADJUST")).isEqualTo("SHORTAGE");
        assertThat(quantityOf("DC", 1, "putaway_wait")).isEqualTo(2);

        assertThat(entrySumOf(904, "ADJUST")).isEqualTo(-1);
    }

    @Test
    @DisplayName("같은 취소가 두 번 와도 한 번만 되돌린다")
    void releasesOnce() {
        reserved(905, "DC", 3);

        publishCanceled(905, "DC", 3, false, "");
        publishCanceled(905, "DC", 3, false, "");

        assertThat(waitForMovement(905, "RELEASE")).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount(905, "RELEASE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "available")).isEqualTo(3);

    }

    // 상품 1을 출발 거점에 예약해 둔다. 이동 요청 때 inventory가 남긴 것과 같다
    private void reserved(long transferId, String locationCode, int quantity) {
        jdbcClient.sql("""
                        INSERT INTO stock (location_code, product_id, reserved)
                        VALUES (:locationCode, 1, :quantity)
                        """)
            .param("locationCode", locationCode)
            .param("quantity", quantity)
            .update();
        jdbcClient.sql("""
                        INSERT INTO reservation (ref_type, ref_id, location_code, product_id, quantity, status)
                        VALUES ('TRANSFER', :refId, :locationCode, 1, :quantity, 'ACTIVE')
                        """)
            .param("refId", String.valueOf(transferId))
            .param("locationCode", locationCode)
            .param("quantity", quantity)
            .update();
    }

    private void publishCanceled(long transferId, String fromLocationCode, int quantity, boolean putawayPending,
                                 String shortages) {
        String payload = """
                {"eventId":"cancel-%d","transferId":%d,"fromLocationCode":"%s",
                 "items":[{"productId":1,"quantity":%d}],"shortages":[%s],"putawayPending":%b,
                 "occurredAt":"2026-10-02T00:00:00Z"}
                """.formatted(transferId, transferId, fromLocationCode, quantity, shortages, putawayPending);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(transferId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "TransferCanceled".getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForMovement(long transferId, String type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = movementCount(transferId, type);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int movementCount(long transferId, String type) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_movement
                        WHERE type = :type AND ref_type = 'TRANSFER' AND ref_id = :refId
                        """)
            .param("type", type)
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private String reasonOf(long transferId, String type) {
        return jdbcClient.sql("""
                        SELECT reason FROM stock_movement
                        WHERE type = :type AND ref_type = 'TRANSFER' AND ref_id = :refId
                        """)
            .param("type", type)
            .param("refId", String.valueOf(transferId))
            .query(String.class)
            .single();
    }

    private int entrySumOf(long transferId, String type) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(e.delta), 0)
                        FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = :type AND m.ref_type = 'TRANSFER' AND m.ref_id = :refId
                        """)
            .param("type", type)
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private String reservationStatusOf(long transferId) {
        return jdbcClient.sql("SELECT status FROM reservation WHERE ref_type = 'TRANSFER' AND ref_id = :refId")
            .param("refId", String.valueOf(transferId))
            .query(String.class)
            .single();
    }

    private int quantityOf(String locationCode, long productId, String column) {
        return jdbcClient.sql("""
                        SELECT %s FROM stock WHERE location_code = :locationCode AND product_id = :productId
                        """.formatted(column))
            .param("locationCode", locationCode)
            .param("productId", productId)
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
