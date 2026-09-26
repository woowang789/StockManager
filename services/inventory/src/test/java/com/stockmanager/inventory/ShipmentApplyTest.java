package com.stockmanager.inventory;

import static org.assertj.core.api.Assertions.assertThat;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentApplyTest {

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
    @DisplayName("운송 이벤트를 받으면 예약이 빠지고 원장에 SHIP 전표가 남는다")
    void appliesShipment() {
        givenReservedOrder("ORD-1", 10, 3);

        publish("ShipmentShipped", "evt-1", "ORD-1", 3);

        assertThat(waitForShipMovement("ORD-1")).isEqualTo(1);
        assertThat(quantityOf("available")).isEqualTo(7);
        assertThat(quantityOf("reserved")).isZero();
        assertThat(reservationStatusOf("ORD-1")).isEqualTo("CONSUMED");
        assertThat(entriesOf("ORD-1")).containsExactly("RESERVED:-3:0");
    }

    @Test
    @DisplayName("같은 운송이 두 번 와도 한 번만 빠진다")
    void appliesShipmentOnce() {
        givenReservedOrder("ORD-2", 10, 3);

        publish("ShipmentShipped", "evt-2", "ORD-2", 3);
        publish("ShipmentShipped", "evt-3", "ORD-2", 3);

        assertThat(waitForShipMovement("ORD-2")).isEqualTo(1);
        sleep(Duration.ofSeconds(2));
        assertThat(shipMovementCount("ORD-2")).isEqualTo(1);
        assertThat(quantityOf("reserved")).isZero();
    }

    @Test
    @DisplayName("아직 다루지 않는 이벤트는 건너뛴다")
    void skipsUnknownEventType() {
        givenReservedOrder("ORD-4", 10, 3);
        publish("ShipmentCanceled", "evt-4", "ORD-4", 3);

        sleep(Duration.ofSeconds(3));
        assertThat(shipMovementCount("ORD-4")).isZero();
        assertThat(quantityOf("reserved")).isEqualTo(3);
    }


    private void givenReservedOrder(String orderNo, int stock, int quantity) {
        stockAdjustmentService.adjust(
            new StockChange("DC", 1, StockState.AVAILABLE, stock), AdjustmentReason.COUNT_DIFF, "test");
        reservationService.reserve(
            new ReserveCommand("ORDER", orderNo, "DC", List.of(new ReserveCommand.Item(1, quantity))), "sales");
    }

    private void publish(String eventType, String eventId, String orderNo, int quantity) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC","items":[{"productId":1,"quantity":%d}],
                 "occurredAt":"2026-09-25T00:00:00Z"}
                """.formatted(eventId, orderNo, quantity);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, orderNo, payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int waitForShipMovement(String orderNo) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            int count = shipMovementCount(orderNo);
            if (count > 0) {
                return count;
            }
            sleep(Duration.ofMillis(200));
        }
        return 0;
    }

    private int shipMovementCount(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = 'SHIP' AND ref_id = :refId")
            .param("refId", orderNo)
            .query(Integer.class)
            .single();
    }

    private String reservationStatusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM reservation WHERE ref_id = :refId")
            .param("refId", orderNo)
            .query(String.class)
            .single();
    }

    private List<String> entriesOf(String orderNo) {
        return jdbcClient.sql("""
                        SELECT CONCAT(e.state, ':', e.delta, ':', e.balance_after)
                        FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                        WHERE m.type = 'SHIP' AND m.ref_id = :refId
                        ORDER BY e.id
                        """)
            .param("refId", orderNo)
            .query(String.class)
            .list();
    }

    private int quantityOf(String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'DC' AND product_id = 1".formatted(column))
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
