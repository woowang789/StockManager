package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class OutboxTest {

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    CircuitBreaker inventoryCircuitBreaker;

    @BeforeEach
    void clearOrdersAndStub() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
        jdbcClient.sql("DELETE FROM outbox").update();
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();
    }

    @Test
    @DisplayName("주문이 RESERVED가 되면 outbox에 이벤트가 남고 곧 발행 표시가 찍힌다")
    void recordsAndPublishesEvent() {
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));

        assertThat(countOf("ORD-1")).isEqualTo(1);
        assertThat(typeOf("ORD-1")).isEqualTo("OrderReserved");
        assertThat(waitForPublishedAt("ORD-1")).isNotNull();
    }

    @Test
    @DisplayName("예약이 거절되면 outbox에 아무것도 남지 않는다")
    void recordsNothingWhenRejected() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(409)));

        orderService.place("ORD-2", List.of(new OrderLine(1, 3)));

        assertThat(countOf("ORD-2")).isZero();
    }

    @Test
    @DisplayName("주문과 무관하게 outbox에 적힌 줄도 발행된다")
    void publishesRowWrittenByAnyone() {
        insertOutbox("ORD-9", null);
        assertThat(waitForPublishedAt("ORD-9")).isNotNull();
    }

    @Test
    @DisplayName("이미 발행 표시가 있는 줄은 다시 보내지 않는다")
    void skipsAlreadyPublished() {
        LocalDateTime publishedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        insertOutbox("ORD-8", publishedAt);

        sleep(Duration.ofSeconds(3));

        assertThat(publishedAtOf("ORD-8")).contains(publishedAt);
    }

    private void insertOutbox(String messageKey, LocalDateTime publishedAt) {
        jdbcClient.sql("""
                        INSERT INTO outbox (topic, message_key, event_type, payload, created_at, published_at)
                        VALUES ('sales.order', :messageKey, 'OrderReserved', :payload, :createdAt, :publishedAt)
                        """)
            .param("messageKey", messageKey)
            .param("payload", "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"orderNo\":\"" + messageKey + "\"}")
            .param("createdAt", LocalDateTime.now())
            .param("publishedAt", publishedAt)
            .update();
    }

    private LocalDateTime waitForPublishedAt(String messageKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<LocalDateTime> publishedAt = publishedAtOf(messageKey);
            if (publishedAt.isPresent()) {
                return publishedAt.get();
            }
            sleep(Duration.ofMillis(200));
        }
        return null;
    }

    private Optional<LocalDateTime> publishedAtOf(String messageKey) {
        return jdbcClient.sql("SELECT published_at FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", messageKey)
            .query(LocalDateTime.class)
            .optional();
    }

    private int countOf(String messageKey) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", messageKey)
            .query(Integer.class)
            .single();
    }

    private String typeOf(String messageKey) {
        return jdbcClient.sql("SELECT event_type FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", messageKey)
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
