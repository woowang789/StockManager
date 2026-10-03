package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.stockmanager.sales.application.PosSaleService;
import com.stockmanager.sales.domain.PosSale;
import com.stockmanager.sales.domain.PosSaleLine;
import com.stockmanager.sales.domain.PosSaleStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;


@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class PosSaleTest {

    private static final int RETRIES = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PosSaleService posSaleService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    CircuitBreaker inventoryCircuitBreaker;

    @BeforeEach
    void clearSalesAndStub() {
        jdbcClient.sql("DELETE FROM pos_sale_item").update();
        jdbcClient.sql("DELETE FROM pos_sale").update();
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();
        inventoryStub.stubFor(WireMock.post("/pos-deductions").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("판매하면 inventory에 매장 재고 차감을 요청하고 COMPLETED가 된다")
    void completesSale() throws Exception {
        sell("""
                {"receiptNo":"R-1","items":[{"productId":1,"quantity":2}]}
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.locationCode").value("STORE"));

        inventoryStub.verify(postRequestedFor(urlEqualTo("/pos-deductions"))
            .withRequestBody(equalToJson("""
                        {"refType":"POS","refId":"R-1","locationCode":"STORE","items":[{"productId":1,"quantity":2}]}
                        """)));
    }

    @Test
    @DisplayName("가용이 모자라 inventory가 거절하면 REJECTED로 끝난다")
    void rejectsWhenStockIsShort() {
        inventoryStub.stubFor(WireMock.post("/pos-deductions").willReturn(aResponse().withStatus(409)));

        PosSale sale = posSaleService.sell("R-2", List.of(new PosSaleLine(1, 9)));

        assertThat(sale.getStatus()).isEqualTo(PosSaleStatus.REJECTED);
    }

    @Test
    @DisplayName("inventory의 결과를 모르면 PENDING으로 남는다")
    void staysPendingWhenResultIsUnknown() {
        inventoryStub.stubFor(WireMock.post("/pos-deductions").willReturn(aResponse().withStatus(500)));

        PosSale sale = posSaleService.sell("R-3", List.of(new PosSaleLine(1, 2)));

        assertThat(sale.getStatus()).isEqualTo(PosSaleStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING 판매는 같은 영수증으로 다시 물어 확정한다")
    void confirmsPendingSale() {
        inventoryStub.stubFor(WireMock.post("/pos-deductions").inScenario("장애 후 복구")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("복구됨"));
        inventoryStub.stubFor(WireMock.post("/pos-deductions").inScenario("장애 후 복구")
            .whenScenarioStateIs("복구됨")
            .willReturn(okJson("{\"movementId\":1}")));

        posSaleService.sell("R-4", List.of(new PosSaleLine(1, 2)));

        posSaleService.confirmPending(Instant.now());

        assertThat(statusOf("R-4")).isEqualTo("COMPLETED");

        posSaleService.confirmPending(Instant.now());
        inventoryStub.verify(2, postRequestedFor(urlEqualTo("/pos-deductions")));
    }

    @Test
    @DisplayName("같은 영수증으로 다시 보내면 처음 판매를 돌려주고 inventory는 다시 부르지 않는다")
    void returnsFirstSaleOnRetry() {
        PosSale first = posSaleService.sell("R-5", List.of(new PosSaleLine(1, 2)));
        PosSale retried = posSaleService.sell("R-5", List.of(new PosSaleLine(2, 7)));

        assertThat(retried.getId()).isEqualTo(first.getId());
        assertThat(retried.getLines()).containsExactly(new PosSaleLine(1, 2));
        inventoryStub.verify(1, postRequestedFor(urlEqualTo("/pos-deductions")));
    }

    @Test
    @DisplayName("같은 영수증이 동시에 여러 번 와도 판매는 하나, 차감 요청도 한 번이다")
    void sellsOnceUnderConcurrentRetries() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PosSale>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(RETRIES)) {
            for (int i = 0; i < RETRIES; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return posSaleService.sell("R-6", List.of(new PosSaleLine(1, 2)));
                }));
            }
            start.countDown();
        }

        List<Long> saleIds = new ArrayList<>();
        for (Future<PosSale> result : results) {
            saleIds.add(result.get().getId());
        }
        assertThat(saleIds).hasSize(RETRIES).containsOnly(saleIds.getFirst());

        inventoryStub.verify(1,postRequestedFor(urlEqualTo("/pos-deductions")));
    }

    @Test
    @DisplayName("같은 상품을 두 줄로 보내면 받지 않는다")
    void rejectsDuplicateProduct() {
        assertThatThrownBy(() -> posSaleService.sell("R-7",
            List.of(new PosSaleLine(1, 1), new PosSaleLine(1, 2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 상품이 두 줄에 있습니다");
        inventoryStub.verify(0, postRequestedFor(urlEqualTo("/pos-deductions")));
    }

    @Test
    @DisplayName("상품 ID가 빠진 항목이 있으면 받지 않는다")
    void rejectsItemWithoutProductId() throws Exception {
        sell("""
                {"receiptNo":"R-8","items":[{"quantity":1}]}
                """)
            .andExpect(status().isBadRequest());

        assertThat(saleCount()).isZero();
    }

    private ResultActions sell(String body) throws Exception {
        return mockMvc.perform(post("/pos-sales")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String statusOf(String receiptNo) {
        return jdbcClient.sql("SELECT status FROM pos_sale WHERE receipt_no = ?")
            .param(receiptNo)
            .query(String.class)
            .single();
    }

    private int saleCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM pos_sale")
            .query(Integer.class)
            .single();
    }
}
