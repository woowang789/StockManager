package com.stockmanager.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import java.util.List;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockAdjustmentApiTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearLedgerAndStock() {
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("조정하면 재고가 바뀌고 원장에 전표와 줄이 남는다.")
    void recordsLedgerOnAdjustment() throws Exception {
        adjust("""
                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":10,"reason":"COUNT_DIFF"}
                """)
            .andExpect(status().isOk());

        assertThat(availableOf("DC", 1)).isEqualTo(10);
        assertThat(entries("DC", 1)).containsExactly("10:10");
        assertThat(actorOfLastMovement()).isEqualTo("admin");
    }

    @Test
    @DisplayName("조정할 때마다 원장에 이동 후 잔액이 쌓인다")
    void accumulatesBalance() throws Exception {
        adjust("""
                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":10,"reason":"COUNT_DIFF"}
                """);
        adjust("""
                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":-6,"reason":"LOST"}
                """);

        assertThat(availableOf("DC", 1)).isEqualTo(4);
        assertThat(entries("DC", 1)).containsExactly("10:10", "-6:4");
    }

    @Test
    @DisplayName("남은 수량보다 많이 빼면 409이고 재고와 원장이 그대로다")
    void rejectsAdjustmentBelowZero() throws Exception {
        adjust("""
                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":3,"reason":"COUNT_DIFF"}
                """);

        adjust("""
            {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":-5,"reason":"LOST"}
            """)
            .andExpect(status().isConflict())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").value("재고가 부족합니다: DC 상품 1의 AVAILABLE 5개"));

        assertThat(availableOf("DC", 1)).isEqualTo(3);
        assertThat(entries("DC", 1)).containsExactly("3:3");
    }

    @Test
    @DisplayName("사유가 없으면 조정할 수 없다")
    void rejectsAdjustmentWithoutReason() throws Exception {
        adjust("""
            {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":10}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.reason").exists());

        assertThat(entries("DC", 1)).isEmpty();
    }

    @Test
    @DisplayName("전표에 요청의 trace_id가 남는다")
    void recordsTraceIdOfRequest() throws Exception{
        // 앞 서비스가 넘겨준 W3C traceparent(버전-trace_id-부모 span-플래그)
        mockMvc.perform(post("/adjustments")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("X-User-Id", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":10,"reason":"COUNT_DIFF"}
                                """))
            .andExpect(status().isOk());

        assertThat(traceIdOfLastMovement()).isEqualTo(TRACE_ID);
    }


    private ResultActions adjust(String body) throws Exception {
        return mockMvc.perform(post("/adjustments")
            .header("X-User-Id", "admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private int availableOf(String locationCode, long productId) {
        return jdbcClient.sql("SELECT available FROM stock WHERE location_code = ? AND product_id = ?")
            .params(locationCode, productId)
            .query(Integer.class)
            .optional()
            .orElse(0);
    }

    // 전표 줄을 "변화량:이동 후 잔액" 형태로 순서대로 본다
    private List<String> entries(String locationCode, long productId) {
        return jdbcClient.sql("""
                        SELECT CONCAT(delta, ':', balance_after)
                        FROM stock_entry
                        WHERE location_code = ? AND product_id = ?
                        ORDER BY id
                        """)
            .params(locationCode, productId)
            .query(String.class)
            .list();
    }

    private String traceIdOfLastMovement() {
        return jdbcClient.sql("SELECT trace_id FROM stock_movement ORDER BY id DESC LIMIT 1")
            .query(String.class)
            .single();
    }

    private String actorOfLastMovement() {
        return jdbcClient.sql("SELECT actor FROM stock_movement ORDER BY id DESC LIMIT 1")
            .query(String.class)
            .single();
    }

}
