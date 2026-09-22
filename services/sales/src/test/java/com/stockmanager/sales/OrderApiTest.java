package com.stockmanager.sales;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @BeforeEach
    void clearOrders() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
        inventoryStub.resetAll();
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
            .andExpect(status().isBadRequest());

        assertThat(orderCount()).isZero();
        inventoryStub.verify(0,postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("없는 주문번호를 조회하면 404다")
    void returnsNotFoundForUnknownOrder() throws Exception {
        mockMvc.perform(get("/orders/NOPE"))
            .andExpect(status().isNotFound());
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
