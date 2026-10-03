package com.stockmanager.inventory;

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
class ReputawayStoreTest {

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
    @DisplayName("취소된 출하의 재적치가 오면 적치대기에서 가용으로 옮겨진다")
    void movesToAvailableAfterShipmentCancel() {
        reserved("ORDER", "ORD-R1", 3);

        // 취소를 기다리지 않고 바로 보낸다. 같은 토픽·키라서 취소가 먼저 처리된다
        publish("wms.shipment", "ORD-R1", "ShipmentCanceled", """
                {"eventId":"cancel-r1","orderNo":"ORD-R1","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3}],"shortages":[],"putawayPending":true,
                 "occurredAt":"2026-10-03T00:00:00Z"}
                """);
        publishStored("wms.shipment", "ORD-R1", 921, 3);

        assertThat(waitForPutaway(921)).isEqualTo(1);
        assertThat(quantityOf("putaway_wait")).isZero();
        assertThat(quantityOf("available")).isEqualTo(3);
        // 유형 사이를 옮길 뿐이라 회사 재고는 그대로다
        assertThat(entrySumOf(921)).isZero();
    }

    @Test
    @DisplayName("취소된 이동의 재적치는 이동의 토픽으로 와도 같게 옮겨진다")
    void movesToAvailableAfterTransferCancel() {
        reserved("TRANSFER", "712", 2);

        publish("wms.transfer", "712", "TransferCanceled", """
                {"eventId":"cancel-712","transferId":712,"fromLocationCode":"DC",
                 "items":[{"productId":1,"quantity":2}],"shortages":[],"putawayPending":true,
                 "occurredAt":"2026-10-03T00:00:00Z"}
                """);
        publishStored("wms.transfer", "712", 922, 2);

        assertThat(waitForPutaway(922)).isEqualTo(1);
        assertThat(quantityOf("putaway_wait")).isZero();
        assertThat(quantityOf("available")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 재적치가 두 번 와도 한 번만 옮겨진다")
    void storesOnce() {
        reserved("ORDER", "ORD-R3", 3);
        publish("wms.shipment", "ORD-R3", "ShipmentCanceled", """
                {"eventId":"cancel-r3","orderNo":"ORD-R3","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3}],"shortages":[],"putawayPending":true,
                 "occurredAt":"2026-10-03T00:00:00Z"}
                """);

        publishStored("wms.shipment", "ORD-R3", 923, 3);
        publishStored("wms.shipment", "ORD-R3", 923, 3);

        assertThat(waitForPutaway(923)).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(putawayCount(923)).isEqualTo(1);
        assertThat(quantityOf("available")).isEqualTo(3);
    }

    // 상품 1을 센터에 예약해 둔다. 주문·이동 요청 때 inventory가 남긴 것과 같다
    private void reserved(String refType, String refId, int quantity) {
        jdbcClient.sql("INSERT INTO stock (location_code, product_id, reserved) VALUES ('DC', 1, :quantity)")
                .param("quantity", quantity)
                .update();
        jdbcClient.sql("""
                        INSERT INTO reservation (ref_type, ref_id, location_code, product_id, quantity, status)
                        VALUES (:refType, :refId, 'DC', 1, :quantity, 'ACTIVE')
                        """)
                .param("refType", refType)
                .param("refId", refId)
                .param("quantity", quantity)
                .update();
    }

    private void publishStored(String topic, String key, long reputawayId, int quantity) {
        publish(topic, key, "ReputawayStored", """
                {"eventId":"stored-%d","reputawayId":%d,"locationCode":"DC",
                 "items":[{"productId":1,"quantity":%d}],"occurredAt":"2026-10-03T00:00:00Z"}
                """.formatted(reputawayId, reputawayId, quantity));
    }

    private void publish(String topic, String key, String eventType, String payload) {
        kafkaTemplate.send(new ProducerRecord<>(topic, null, key, payload,
                        List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                                eventType.getBytes(StandardCharsets.UTF_8)))))
                .join();
    }

    private int waitForPutaway(long reputawayId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = putawayCount(reputawayId);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    // 재적치의 전표는 출하·이동이 아니라 재적치 작업을 문서로 단다
    private int putawayCount(long reputawayId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_movement
                        WHERE type = 'PUTAWAY' AND ref_type = 'REPUTAWAY' AND ref_id = :refId
                        """)
                .param("refId", String.valueOf(reputawayId))
                .query(Integer.class)
                .single();
    }

    private int entrySumOf(long reputawayId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(e.delta), 0)
                        FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = 'PUTAWAY' AND m.ref_type = 'REPUTAWAY' AND m.ref_id = :refId
                        """)
                .param("refId", String.valueOf(reputawayId))
                .query(Integer.class)
                .single();
    }

    private int quantityOf(String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'DC' AND product_id = 1".formatted(column))
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
