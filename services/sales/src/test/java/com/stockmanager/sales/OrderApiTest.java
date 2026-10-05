package com.stockmanager.sales;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    CircuitBreaker inventoryCircuitBreaker;

    @BeforeEach
    void clearOrders() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();
        inventoryStub.stubFor(WireMock.post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("주문하면 inventory에 예약을 요청하고 RESERVED가 된다")
    void reservesOrder() throws Exception{
        place("""
            {"orderNo":"ORD-1","items":[{"productId":1,"quantity":3},{"productId":2,"quantity":1}]}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(get("/orders/ORD-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderNo").value("ORD-1"))
            .andExpect(jsonPath("$.locationCode").value("DC"))
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].productId").value(1))
            .andExpect(jsonPath("$.items[0].quantity").value(3));

        inventoryStub.verify(1, postRequestedFor(urlEqualTo("/reservations"))
            .withHeader("X-User-Id", equalTo("sales"))
            .withRequestBody(equalToJson("""
                {"refType":"ORDER","refId":"ORD-1","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3},{"productId":2,"quantity":1}]}
                """)));
    }

    @Test
    @DisplayName("같은 상품이 두 줄이면 접수하지 않고 inventory도 부르지 않는다")
    void rejectsDuplicateProductLines() throws Exception {
        place("""
            {"orderNo":"ORD-2","items":[{"productId":1,"quantity":3},{"productId":1,"quantity":2}]}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("같은 상품이 두 줄에 있습니다: 상품 1"));

        assertThat(orderCount()).isZero();
        inventoryStub.verify(0,postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("없는 주문번호를 조회하면 404다")
    void returnsNotFoundForUnknownOrder() throws Exception {
        mockMvc.perform(get("/orders/NOPE"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("주문한 요청의 trace와 사용자 ID가 inventory 호출까지 이어진다")
    void carriesTraceAndUserToInventory() throws Exception {
        // 게이트웨이가 넘겨준 W3C traceparent(버전-trace_id-부모 span-플래그)와 사용자 ID
        mockMvc.perform(post("/orders")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("X-User-Id", "customer-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"orderNo":"ORD-T1","items":[{"productId":1,"quantity":1}]}
                                """))
            .andExpect(status().isOk());

        // trace_id는 이어지고, 사용자 ID는 설정의 sales 대신 주문한 사용자 하나만 간다
        inventoryStub.verify(postRequestedFor(urlEqualTo("/reservations"))
            .withHeader("traceparent", containing(TRACE_ID))
            .withHeader("X-User-Id", havingExactly("customer-1")));
    }


    private ResultActions place(String body) throws Exception {
        return mockMvc.perform(post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private int orderCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM sales_order")
            .query(Integer.class)
            .single();
    }

}
