package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import java.time.Instant;
import java.util.List;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
public class PendingOrderRetryTest {

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
        inventoryCircuitBreaker.reset();
    }

    @Test
    @DisplayName("응답을 못 받은 주문은 같은 주문번호로 다시 물어서 확정한다")
    void confirmsOrderWhoseResponseWasLost() {
        inventoryStub.stubFor(post("/reservations").inScenario("응답 유실")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"movementId\":1}").withFixedDelay(5000))
            .willSetStateTo("처리됨"));

        inventoryStub.stubFor(post("/reservations").inScenario("응답 유실")
            .whenScenarioStateIs("처리됨")
            .willReturn(okJson("{\"movementId\":1}")));

        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
        assertThat(statusOf("ORD-1")).isEqualTo("PENDING");

        orderService.confirmPending(Instant.now());
        assertThat(statusOf("ORD-1")).isEqualTo("RESERVED");

        orderService.confirmPending(Instant.now());
        inventoryStub.verify(2, postRequestedFor(urlEqualTo("/reservations"))
            .withRequestBody(equalToJson("""
                {"refType":"ORDER","refId":"ORD-1","locationCode":"DC","items":[{"productId":1,"quantity":3}]}
                """)));
    }

    @Test
    @DisplayName("다시 물었을 때 재고가 모자라면 REJECTED가 된다")
    void rejectsWhenRetryFindsStockShort() {
        inventoryStub.stubFor(post("/reservations").inScenario("장애 후 재고 부족")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("복구됨"));
        inventoryStub.stubFor(post("/reservations").inScenario("장애 후 재고 부족")
            .whenScenarioStateIs("복구됨")
            .willReturn(aResponse().withStatus(409)));

        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
        orderService.confirmPending(Instant.now());

        assertThat(statusOf("ORD-1")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("기준 시각 뒤에 만든 PENDING 주문은 건드리지 않는다")
    void leavesRecentPendingOrdersAlone() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        Instant beforeOrder = Instant.now().minusSeconds(30);

        orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
        orderService.confirmPending(beforeOrder);

        assertThat(statusOf("ORD-1")).isEqualTo("PENDING");
        inventoryStub.verify(1, postRequestedFor(urlEqualTo("/reservations")));
    }

    private String statusOf(String orderNo) {
        return jdbcClient.sql("SELECT status FROM sales_order WHERE order_no = ?")
            .param(orderNo)
            .query(String.class)
            .single();
    }
}
