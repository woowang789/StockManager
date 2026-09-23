package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.SalesOrder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
public class InventoryCircuitBreakerTest {

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
        inventoryStub.resetAll();
        // 서킷은 컨텍스트에 남는 상태다. 테스트마다 닫힌 상태에서 시작한다
        inventoryCircuitBreaker.reset();
    }

    @Test
    @DisplayName("연달아 실패하면 서킷이 열리고, 그다음 주문은 inventory를 부르지도 않는다")
    void stopsCallingInventoryOnceOpen() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));

        placeOrders(1,5);
        assertThat(inventoryCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        SalesOrder order = orderService.place("ORD-6", List.of(new OrderLine(1, 3)));
        inventoryStub.verify(5, postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("재고 부족(409)은 장애가 아니라 답이므로 서킷을 열지 않는다")
    void keepsCircuitClosedOnConflict() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(409)));

        placeOrders(1, 10);

        assertThat(inventoryCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(statusOf("ORD-10")).isEqualTo("REJECTED");
        inventoryStub.verify(10, postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("열린 뒤 시험 호출이 성공하면 서킷이 다시 닫힌다")
    void closesAgainAfterSuccessfulTrialCalls() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        placeOrders(1, 5);

        inventoryCircuitBreaker.transitionToHalfOpenState();
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        placeOrders(6, 7);

        assertThat(inventoryCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(statusOf("ORD-7")).isEqualTo("RESERVED");
    }


    private void placeOrders(int from, int to) {
        for (int number = from; number <= to; number++) {
            orderService.place("ORD-" + number, List.of(new OrderLine(1, 1)));
        }
    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM sales_order WHERE order_no = ?")
            .param(orderNo)
            .query(String.class)
            .single();
    }
}
