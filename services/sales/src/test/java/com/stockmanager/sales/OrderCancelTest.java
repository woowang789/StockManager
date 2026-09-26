package com.stockmanager.sales;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.OrderStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class OrderCancelTest {

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    CircuitBreaker inventoryCircuitBreaker;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clearOrdersAndStub() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
        jdbcClient.sql("DELETE FROM outbox").update();
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("예약된 주문을 취소 요청하면 CANCEL_REQUESTED가 되고 이벤트가 남는다")
    void requestCancel() {
        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));

        assertThat(orderService.requestCancel("ORD-1").getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);

        assertThat(eventCountOf("ORD-1", "OrderCancelRequested")).isEqualTo(1);
    }

    @Test
    @DisplayName("두 번 요청해도 이벤트는 하나다")
    void requestsCancelOnce() {
        orderService.place("ORD-2", List.of(new OrderLine(1, 3)));

        orderService.requestCancel("ORD-2");
        orderService.requestCancel("ORD-2");

        assertThat(eventCountOf("ORD-2", "OrderCancelRequested")).isEqualTo(1);
    }

    @Test
    @DisplayName("예약 결과를 모르는 PENDING 주문은 취소할 수 없다")
    void rejectsCancelWhilePending() {
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        orderService.place("ORD-3", List.of(new OrderLine(1, 3)));

        assertThatThrownBy(() -> orderService.requestCancel("ORD-3"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("취소할 수 없는 상태입니다");
        assertThat(eventCountOf("ORD-3", "OrderCancelRequested")).isZero();
    }

    @Test
    @DisplayName("취소를 요청했지만 이미 운송됐으면 SHIPPED로 끝난다")
    void shippingWinsOverCancelRequest() {
        orderService.place("ORD-4", List.of(new OrderLine(1, 3)));
        orderService.requestCancel("ORD-4");

        publishShipped("evt-4", "ORD-4");

        assertThat(waitForStatus("ORD-4", "SHIPPED")).isTrue();
    }


    private void publishShipped(String eventId, String orderNo) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC","items":[{"productId":1,"quantity":3}],
                 "occurredAt":"2026-09-26T00:00:00Z"}
                """.formatted(eventId, orderNo);
        kafkaTemplate.send(new ProducerRecord<>("wms.shipment", null, orderNo, payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE,
                    "ShipmentShipped".getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private int eventCountOf(String orderNo, String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox WHERE message_key = :orderNo AND event_type = :eventType
                        """)
            .param("orderNo", orderNo)
            .param("eventType", eventType)
            .query(Integer.class)
            .single();
    }

    private boolean waitForStatus(String orderNo, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(statusOf(orderNo))) {
                return true;
            }
            try {
                Thread.sleep(Duration.ofMillis(200));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        return false;
    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM sales_order WHERE order_no = ?")
            .param(orderNo)
            .query(String.class)
            .single();
    }
}
