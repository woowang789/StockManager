package com.stockmanager.wms;

import com.stockmanager.wms.application.ShipmentService;
import com.stockmanager.wms.domain.ShipmentLine;
import com.stockmanager.wms.domain.ShipmentStatus;
import com.stockmanager.wms.infrastructure.ShipmentRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentShipTest {
    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM shipment_item").update();
        jdbcClient.sql("DELETE FROM shipment").update();
        jdbcClient.sql("DELETE FROM processed_event").update();
        jdbcClient.sql("DELETE FROM outbox").update();
    }

    @Test
    @DisplayName("운송하면 SHIPPED가 되고 ShipmentShipped가 outbox에 남아 발행된다")
    void shipsAndRecordsEvent() {
        shipmentRepository.insert("ORD-1", "DC", List.of(new ShipmentLine(1, 3)));
        shipmentService.pick("ORD-1");
        shipmentService.pack("ORD-1");

        assertThat(shipmentService.ship("ORD-1").status()).isEqualTo(ShipmentStatus.SHIPPED);

        assertThat(outboxCount("ORD-1")).isEqualTo(1);
        assertThat(eventTypeOf("ORD-1")).isEqualTo("ShipmentShipped");
        assertThat(waitForPublishedAt("ORD-1")).isNotNull();
    }

    @Test
    @DisplayName("두 번 운송해도 이벤트는 하나다")
    void recordsEventOnce() {
        shipmentRepository.insert("ORD-2", "DC", List.of(new ShipmentLine(1, 3)));
        shipmentService.pick("ORD-2");
        shipmentService.pack("ORD-2");

        shipmentService.ship("ORD-2");
        shipmentService.ship("ORD-2");

        assertThat(outboxCount("ORD-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("피킹 패킹을 건너뛰고 운송할 수 없다")
    void rejectsShipBeforePacking() {
        shipmentRepository.insert("ORD-3", "DC", List.of(new ShipmentLine(1, 3)));

        assertThatThrownBy(() -> shipmentService.ship("ORD-3"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("운송할 수 없는 상태입니다");
        assertThat(outboxCount("ORD-3")).isZero();
    }

    @Test
    @DisplayName("없는 출하 작업은 운송할 수 없다")
    void rejectsUnknownShipment() {
        assertThatThrownBy(() -> shipmentService.ship("ORD-404"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("출하 작업이 없습니다");
    }



    private int outboxCount(String messageKey) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", messageKey)
            .query(Integer.class)
            .single();
    }

    private String eventTypeOf(String messageKey) {
        return jdbcClient.sql("SELECT event_type FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", messageKey)
            .query(String.class)
            .single();
    }

    private LocalDateTime waitForPublishedAt(String messageKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<LocalDateTime> publishedAt =
                jdbcClient.sql("SELECT published_at FROM outbox WHERE message_key = :messageKey")
                    .param("messageKey", messageKey)
                    .query(LocalDateTime.class)
                    .optional();
            if (publishedAt.isPresent()) {
                return publishedAt.get();
            }
            try {
                Thread.sleep(Duration.ofMillis(200));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        return null;
    }
}
