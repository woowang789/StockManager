package com.stockmanager.inventory;

import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.inventory.application.ReservationService;
import com.stockmanager.inventory.application.StockAdjustmentService;
import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.ReserveCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockState;
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
class ShipmentReleaseTest {

    private static final String TOPIC = "wms.shipment";

    @Autowired
    ReservationService reservationService;

    @Autowired
    StockAdjustmentService stockAdjustmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM reservation").update();
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("피킹 전에 취소하면 예약이 가용으로 돌아온다")
    void returnsToAvailable() {
        givenReservedOrder("REL-1", 10, 3);

        publishCanceled("evt-1", "REL-1", 3, 0, false);

        assertThat(waitForMovement("REL-1", "RELEASE")).isEqualTo(1);
        assertThat(quantityOf(1,"available")).isEqualTo(10);
        assertThat(quantityOf(1,"reserved")).isZero();
        assertThat(quantityOf(1,"putaway_wait")).isZero();
        assertThat(reservationStatusOf("REL-1")).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("피킹 뒤에 취소하면 적치대기로 돌아온다")
    void returnsToPutawayWait() {
        givenReservedOrder("REL-2", 10, 3);

        publishCanceled("evt-2", "REL-2", 3, 0, true);

        assertThat(waitForMovement("REL-2", "RELEASE")).isEqualTo(1);
        assertThat(quantityOf(1,"available")).isEqualTo(7);
        assertThat(quantityOf(1,"reserved")).isZero();
        assertThat(quantityOf(1,"putaway_wait")).isEqualTo(3);
    }

    @Test
    @DisplayName("결품이 있으면 되돌린 뒤 그만큼 조정으로 뺀다")
    void adjustsShortageOut() {
        givenReservedOrder("REL-3", 10, 3);

        publishCanceled("evt-3", "REL-3", 3, 2, false);

        assertThat(waitForMovement("REL-3", "ADJUST")).isEqualTo(1);

        assertThat(quantityOf(1,"available")).isEqualTo(8);
        assertThat(quantityOf(1,"reserved")).isZero();
        assertThat(reasonOf("REL-3")).isEqualTo(AdjustmentReason.SHORTAGE.name());
    }

    @Test
    @DisplayName("같은 취소가 두 번 와도 한 번만 반영된다")
    void releasesOnce() {
        givenReservedOrder("REL-4", 10, 3);

        publishCanceled("evt-4", "REL-4", 3, 1, false);
        publishCanceled("evt-5", "REL-4", 3, 1, false);

        assertThat(waitForMovement("REL-4", "RELEASE")).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(movementCount("REL-4", "RELEASE")).isEqualTo(1);
        assertThat(movementCount("REL-4", "ADJUST")).isEqualTo(1);
        assertThat(quantityOf(1,"available")).isEqualTo(9);
    }

    @Test
    @DisplayName("결품이 두 품목이어도 모두 반영된다")
    void adjustsShortagesOfTwoProducts() {
        stockAdjustmentService.adjust(
            new StockChange("DC", 1, StockState.AVAILABLE, 10), AdjustmentReason.COUNT_DIFF, "test");
        stockAdjustmentService.adjust(
            new StockChange("DC", 2, StockState.AVAILABLE, 10), AdjustmentReason.COUNT_DIFF, "test");
        reservationService.reserve(new ReserveCommand("ORDER", "REL-5", "DC",
            List.of(new ReserveCommand.Item(1, 3), new ReserveCommand.Item(2, 4))), "sales");

        publishCanceledOfTwoProducts("evt-6", "REL-5");

        assertThat(waitForMovement("REL-5", "RELEASE")).isEqualTo(1);

        assertThat(quantityOf(1,"available")).isEqualTo(8);
        assertThat(quantityOf(2,"available")).isEqualTo(9);
        assertThat(quantityOf(1, "reserved")).isZero();
        assertThat(quantityOf(2, "reserved")).isZero();
        assertThat(reservationStatusOf("REL-5")).isEqualTo("RELEASED");
        assertThat(movementCount("REL-5", "ADJUST")).isEqualTo(1);
        assertThat(entryCountOf("REL-5", "ADJUST")).isEqualTo(2);
    }

    private void publishCanceledOfTwoProducts(String eventId, String orderNo) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3},{"productId":2,"quantity":4}],
                 "shortages":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}],
                 "picked":false,"occurredAt":"2026-09-26T00:00:00Z"}
                """.formatted(eventId, orderNo);
        send(orderNo, payload);
    }


    private void givenReservedOrder(String orderNo, int stock, int quantity) {
        stockAdjustmentService.adjust(
            new StockChange("DC", 1, StockState.AVAILABLE, stock), AdjustmentReason.COUNT_DIFF, "test");
        reservationService.reserve(
            new ReserveCommand("ORDER", orderNo, "DC", List.of(new ReserveCommand.Item(1, quantity))), "sales");
    }

    private void publishCanceled(String eventId, String orderNo, int quantity, int shortage, boolean picked) {
        String shortages = shortage == 0 ? "" : """
                {"productId":1,"quantity":%d}""".formatted(shortage);
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC",
                 "items":[{"productId":1,"quantity":%d}],"shortages":[%s],"picked":%b,
                 "occurredAt":"2026-09-26T00:00:00Z"}
                """.formatted(eventId, orderNo, quantity, shortages, picked);
        send(orderNo, payload);
    }

    private void send(String orderNo, String payload) {
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, orderNo, payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "ShipmentCanceled".getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForMovement(String orderNo, String type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = movementCount(orderNo, type);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int movementCount(String orderNo, String type) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = :type AND ref_id = :refId")
            .param("type", type)
            .param("refId", orderNo)
            .query(Integer.class)
            .single();
    }

    private int entryCountOf(String orderNo, String type) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = :type AND m.ref_id = :refId
                        """)
            .param("type", type)
            .param("refId", orderNo)
            .query(Integer.class)
            .single();
    }

    private String reasonOf(String orderNo) {
        return jdbcClient.sql("SELECT reason FROM stock_movement WHERE type = 'ADJUST' AND ref_id = :refId")
            .param("refId", orderNo)
            .query(String.class)
            .single();
    }

    private String reservationStatusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM reservation WHERE ref_id = :refId LIMIT 1")
            .param("refId", orderNo)
            .query(String.class)
            .single();
    }

    private int quantityOf(long productId, String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'DC' AND product_id = :productId"
                .formatted(column))
            .param("productId", productId)
            .query(Integer.class)
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
