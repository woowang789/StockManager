package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.OrderStatus;
import com.stockmanager.sales.domain.SalesOrder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Duration;
import java.util.List;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class OrderReservationTest {

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
        inventoryCircuitBreaker.reset();
        inventoryStub.resetAll();
    }

    @Test
    @DisplayName("재고가 모자라면 주문이 REJECTED가 된다")
    void rejectsOrderWhenStockIsShort() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse()
            .withStatus(409)
            .withHeader("Content-Type","application/json")
            .withBody("{\"message\":\"재고가 부족합니다: DC 상품 1의 AVAILABLE 3개\"}")));

        SalesOrder order = orderService.place("ORD-1", List.of(new OrderLine(1, 3)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(statusOf("ORD-1")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("inventory가 500을 주면 결과를 모르는 것이라 주문을 PENDING으로 둔다")
    void keepsPendingOrderWhenInventoryFails() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));

        SalesOrder order = orderService.place("ORD-1", List.of(new OrderLine(1, 3)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf("ORD-1")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("inventory가 늦으면 끝까지 기다리지 않고 주문을 PENDING으로 둔다")
    void givesUpOnSlowInventory() {
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}").withFixedDelay(5000)));

        long start = System.nanoTime();
        SalesOrder order = orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));

    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM sales_order WHERE order_no = ?")
            .param(orderNo)
            .query(String.class)
            .single();
    }
}
