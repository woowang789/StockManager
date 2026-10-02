package com.stockmanager.inventory;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.TransferReceived;
import com.stockmanager.inventory.application.TransferService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferReceiveTest {

    private static final String TOPIC = "wms.transfer";

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    TransferService transferService;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
        jdbcClient.sql("DELETE FROM reservation").update();
    }

    @Test
    @DisplayName("매장에 도착해 검수하면 이동중이 가용으로 풀리고 줄 합계는 0이다")
    void releasesInTransitAtStore() {
        dispatched(801, "DC", "STORE", 3);

        publishReceived(801, "STORE", false, item(1, 3, 0));

        assertThat(waitForMovement(801, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(quantityOf("STORE", 1, "in_transit")).isZero();
        assertThat(quantityOf("STORE", 1, "available")).isEqualTo(3);

        assertThat(entrySumOf(801, "TRANSFER_RECEIVE")).isZero();
    }

    @Test
    @DisplayName("모자란 수량은 상품이동 때 보낸 수량과 비교해 이동 중 분실로 조정된다")
    void adjustsShortfallAsTransitLoss() {
        dispatched(802, "DC", "STORE", 3);

        publishReceived(802, "STORE", false, item(1, 2, 0));

        assertThat(waitForMovement(802, "ADJUST")).isEqualTo(1);
        assertThat(quantityOf("STORE", 1, "available")).isEqualTo(2);

        assertThat(quantityOf("STORE", 1, "in_transit")).isZero();
        assertThat(reasonOf(802, "ADJUST")).isEqualTo("TRANSIT_LOSS");
        assertThat(entrySumOf(802, "ADJUST")).isEqualTo(-1);
    }


    @Test
    @DisplayName("파손은 분실이 아니라 불량으로 받는다")
    void damagedArrivesAsDefective() {
        dispatched(803, "DC", "STORE", 3);

        publishReceived(803, "STORE", false, item(1, 2, 1));

        assertThat(waitForMovement(803, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(quantityOf("STORE", 1, "available")).isEqualTo(2);
        assertThat(quantityOf("STORE", 1, "defective")).isEqualTo(1);
        assertThat(quantityOf("STORE", 1, "in_transit")).isZero();

        assertThat(movementCount(803, "ADJUST")).isZero();
    }

    @Test
    @DisplayName("센터에 도착하면 적치대기로 받고, 적치는 입고 문서의 전표로 가용이 된다")
    void arrivesAtCenterThenStored() {
        dispatched(804, "STORE", "DC", 3);

        publishReceived(804, "DC", true, item(1, 3, 0));
        assertThat(waitForMovement(804, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "putaway_wait")).isEqualTo(3);

        publishStored(804, 1804, "DC", 1, 3);

        assertThat(waitForMovement("INBOUND", 1804, "PUTAWAY")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "available")).isEqualTo(3);
    }

    @Test
    @DisplayName("여러 품목이 모자라도 분실 조정은 전표 하나다")
    void oneLossMovementForManyProducts() {
        dispatched(805, "DC", "STORE", 3, 5);

        publishReceived(805, "STORE", false, item(1, 2, 0), item(2, 3, 0));

        assertThat(waitForMovement(805, "ADJUST")).isEqualTo(1);
        assertThat(entryCountOf(805, "ADJUST")).isEqualTo(2);
        assertThat(quantityOf("STORE", 1, "in_transit")).isZero();
        assertThat(quantityOf("STORE", 2, "in_transit")).isZero();
    }

    @Test
    @DisplayName("같은 검수가 두 번 와도 한 번만 반영된다")
    void receivesOnce() {
        dispatched(806, "DC", "STORE", 3);

        publishReceived(806, "STORE", false, item(1, 2, 0));
        publishReceived(806, "STORE", false, item(1, 2, 0));

        assertThat(waitForMovement(806, "ADJUST")).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount(806, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(movementCount(806, "ADJUST")).isEqualTo(1);
        assertThat(quantityOf("STORE", 1, "available")).isEqualTo(2);
    }

    @Test
    @DisplayName("상품이동과 도착 검수를 연달아 보내도 순서대로 반영된다")
    void dispatchThenReceiveInOrder() {
        reserved(807, "DC", 3);

        publishDispatched(807, "DC", "STORE", 3);
        publishReceived(807, "STORE", false, item(1, 3, 0));

        assertThat(waitForMovement(807, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "reserved")).isZero();
        assertThat(quantityOf("STORE", 1, "in_transit")).isZero();
        assertThat(quantityOf("STORE", 1, "available")).isEqualTo(3);
    }

    @Test
    @DisplayName("상품이동이 반영되지 않은 도착 검수는 이유를 밝히고 반영하지 않는다")
    void rejectsReceiveWithoutDispatch() {
        TransferReceived event = new TransferReceived("recv-808", 808, "STORE",
            List.of(new TransferReceived.Item(1, 3, 0)), false, Instant.parse("2026-09-30T00:00:00Z"));

        assertThatThrownBy(() -> transferService.receive(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("상품이동이 반영되지 않은 도착 검수입니다");
        assertThat(movementCount(808, "TRANSFER_RECEIVE")).isZero();
    }

    @Test
    @DisplayName("도착 검수도 거점을 보지 않고 wms가 알린 대로 넣는다")
    void followsWhatWmsReported() {
        dispatched(809, "STORE", "DC", 3);

        publishReceived(809, "DC", false, item(1, 3, 0));

        assertThat(waitForMovement(809, "TRANSFER_RECEIVE")).isEqualTo(1);
        assertThat(quantityOf("DC", 1, "available")).isEqualTo(3);
        assertThat(quantityOf("DC", 1, "putaway_wait")).isZero();

    }


    // 출발 거점에 예약을 잡아 두고 상품이동을 반영한다. 도착 거점의 이동중과 DISPATCH 전표가 생긴다
    private void dispatched(long transferId, String from, String to, int... quantities) {
        reserved(transferId, from, quantities);
        publishDispatched(transferId, from, to, quantities);
        assertThat(waitForMovement(transferId, "DISPATCH")).isEqualTo(1);
    }

    // 상품 번호는 1부터 차례로 붙는다
    private void reserved(long transferId, String locationCode, int... quantities) {
        for (int i = 0; i < quantities.length; i++) {
            jdbcClient.sql("""
                            INSERT INTO stock (location_code, product_id, reserved)
                            VALUES (:locationCode, :productId, :quantity)
                            """)
                .param("locationCode", locationCode)
                .param("productId", i + 1)
                .param("quantity", quantities[i])
                .update();
            jdbcClient.sql("""
                            INSERT INTO reservation (ref_type, ref_id, location_code, product_id, quantity, status)
                            VALUES ('TRANSFER', :refId, :locationCode, :productId, :quantity, 'ACTIVE')
                            """)
                .param("refId", String.valueOf(transferId))
                .param("locationCode", locationCode)
                .param("productId", i + 1)
                .param("quantity", quantities[i])
                .update();
        }
    }

    private void publishDispatched(long transferId, String from, String to, int... quantities) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            items.add("{\"productId\":%d,\"quantity\":%d}".formatted(i + 1, quantities[i]));
        }
        publish(transferId, "TransferDispatched", """
                {"eventId":"dsp-%d","transferId":%d,"fromLocationCode":"%s","toLocationCode":"%s",
                 "items":[%s],"occurredAt":"2026-09-30T00:00:00Z"}
                """.formatted(transferId, transferId, from, to, String.join(",", items)));
    }

    private static String item(long productId, int good, int defective) {
        return """
                {"productId":%d,"goodQuantity":%d,"defectiveQuantity":%d}""".formatted(productId, good, defective);
    }

    private void publishReceived(long transferId, String toLocationCode, boolean putawayPending, String... items) {
        publish(transferId, "TransferReceived", """
                {"eventId":"recv-%d","transferId":%d,"toLocationCode":"%s",
                 "items":[%s],"putawayPending":%b,"occurredAt":"2026-09-30T00:00:00Z"}
                """.formatted(transferId, transferId, toLocationCode, String.join(",", items), putawayPending));
    }

    // 이동으로 온 입고의 적치. 순서 때문에 이동 토픽으로 오지만 이벤트는 공급사 적치와 같다
    private void publishStored(long transferId, long inboundId, String locationCode, long productId, int quantity) {
        publish(transferId, "InboundStored", """
                {"eventId":"stored-%d","inboundId":%d,"locationCode":"%s",
                 "items":[{"productId":%d,"quantity":%d}],"occurredAt":"2026-09-30T00:00:00Z"}
                """.formatted(inboundId, inboundId, locationCode, productId, quantity));
    }

    private void publish(long transferId, String eventType, String payload) {
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, String.valueOf(transferId), payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForMovement(long transferId, String type) {
        return waitForMovement("TRANSFER", transferId, type);
    }

    private int waitForMovement(String refType, long refId, String type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = movementCount(refType, refId, type);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int movementCount(long transferId, String type) {
        return movementCount("TRANSFER", transferId, type);
    }

    private int movementCount(String refType, long refId, String type) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_movement
                        WHERE type = :type AND ref_type = :refType AND ref_id = :refId
                        """)
            .param("type", type)
            .param("refType", refType)
            .param("refId", String.valueOf(refId))
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
        return jdbcClient.sql(entryQuery("COALESCE(SUM(e.delta), 0)"))
            .param("type", type)
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private int entryCountOf(long transferId, String type) {
        return jdbcClient.sql(entryQuery("COUNT(*)"))
            .param("type", type)
            .param("refId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }

    private String entryQuery(String select) {
        return """
                SELECT %s
                FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                WHERE m.type = :type AND m.ref_type = 'TRANSFER' AND m.ref_id = :refId
                """.formatted(select);
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
