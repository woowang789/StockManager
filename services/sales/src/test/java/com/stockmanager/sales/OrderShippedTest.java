package com.stockmanager.sales;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
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

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class OrderShippedTest {

    private static final String TOPIC = "wms.shipment";

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
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("운송 이벤트를 받으면 주문이 SHIPPED로 끝난다")
    void marksOrderShipped() {
        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));

        publish("ShipmentShipped", "evt-1", "ORD-1");

        assertThat(waitForStatus("ORD-1", "SHIPPED")).isTrue();
    }

    @Test
    @DisplayName("운송 이벤트가 두 번 와도 SHIPPED 그대로다")
    void keepsShippedOnRepeatedEvent() {
        orderService.place("ORD-2", List.of(new OrderLine(1, 3)));

        publish("ShipmentShipped", "evt-2", "ORD-2");
        assertThat(waitForStatus("ORD-2", "SHIPPED")).isTrue();

        publish("ShipmentShipped", "evt-3", "ORD-2");
        sleep(Duration.ofSeconds(2));
        assertThat(statusOf("ORD-2")).isEqualTo("SHIPPED");
    }


    private void publish(String eventType, String eventId, String orderNo) {
        String payload = """
                {"eventId":"%s","orderNo":"%s","locationCode":"DC","items":[{"productId":1,"quantity":3}],
                 "occurredAt":"2026-09-25T00:00:00Z"}
                """.formatted(eventId, orderNo);

        kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, orderNo, payload,
                List.of(new RecordHeader(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8)))))
            .join();
    }

    private boolean waitForStatus(String orderNo, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(statusOf(orderNo))) {
                return true;
            }
            sleep(Duration.ofMillis(200));
        }
        return false;
    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM sales_order WHERE order_no = ?")
            .param(orderNo)
            .query(String.class)
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
