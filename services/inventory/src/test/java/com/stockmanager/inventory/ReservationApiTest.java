package com.stockmanager.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockmanager.inventory.application.StockAdjustmentService;
import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockState;
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
class ReservationApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StockAdjustmentService stockAdjustmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM reservation").update();
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("예약하면 가용이 예약으로 옮겨 가고 전표 한 장에 줄이 둘 남는다.")
    void movesAvailableToReserved() throws Exception {
        given("DC", 1, 10);

        reserve("""
                {"refType":"ORDER","refId":"ORD-1","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3}]}
                """)
            .andExpect(status().isOk());

        assertThat(quantityOf("available", "DC", 1)).isEqualTo(7);
        assertThat(quantityOf("reserved", "DC", 1)).isEqualTo(3);
        assertThat(entriesOfLastMovement()).containsExactly("AVAILABLE:-3:7", "RESERVED:3:3");
        assertThat(activeQuantityOf("ORD-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("항목이 하나라도 모자라면 한 개도 예약되지 않는다")
    void reservesAllOrNothing() throws Exception {
        given("DC", 1, 10);
        given("DC", 2, 1);

        reserve("""
                {"refType":"ORDER","refId":"ORD-2","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3},{"productId":2,"quantity":5}]}
                """)
            .andExpect(status().isConflict());

        assertThat(quantityOf("available", "DC", 1)).isEqualTo(10);
        assertThat(quantityOf("reserved", "DC", 1)).isZero();
        assertThat(activeQuantityOf("ORD-2")).isZero();
        assertThat(reserveMovementCount()).isZero();
    }

    @Test
    @DisplayName("수량이 0 이하면 예약할 수 없다")
    void rejectNonPositiveQuantity() throws Exception {
        given("DC", 1, 10);

        reserve("""
                {"refType":"ORDER","refId":"ORD-3","locationCode":"DC",
                 "items":[{"productId":1,"quantity":0}]}
                """)
            .andExpect(status().isBadRequest());

        assertThat(quantityOf("available", "DC", 1)).isEqualTo(10);
    }

    @Test
    @DisplayName("상품 ID가 빠진 항목이 있으면 예약할 수 없다")
    void rejectsItemWithoutProductId() throws Exception {
        reserve("""
                {"refType":"ORDER","refId":"ORD-4","locationCode":"DC",
                 "items":[{"quantity":1}]}
                """)
            .andExpect(status().isBadRequest());

        assertThat(reserveMovementCount()).isZero();
    }




    private void given(String locationCode, long productId, int quantity) {
        stockAdjustmentService.adjust(
            new StockChange(locationCode, productId, StockState.AVAILABLE, quantity),
            AdjustmentReason.COUNT_DIFF, "test");
    }

    private ResultActions reserve(String body) throws Exception {
        return mockMvc.perform(post("/reservations")
            .header("X-User-Id", "sales")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private int quantityOf(String column, String locationCode, long productId) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = ? AND product_id = ?".formatted(column))
            .params(locationCode, productId)
            .query(Integer.class)
            .optional()
            .orElse(0);
    }

    // 마지막 전표의 줄을 "재고유형:변화량:이동 후 잔액" 형태로 본다
    private List<String> entriesOfLastMovement() {
        return jdbcClient.sql("""
                        SELECT CONCAT(state, ':', delta, ':', balance_after)
                        FROM stock_entry
                        WHERE movement_id = (SELECT MAX(id) FROM stock_movement)
                        ORDER BY id
                        """)
            .query(String.class)
            .list();
    }

    private int activeQuantityOf(String refId) {
        return jdbcClient.sql("SELECT COALESCE(SUM(quantity), 0) FROM reservation WHERE ref_id = ? AND status = 'ACTIVE'")
            .param(refId)
            .query(Integer.class)
            .single();
    }

    private int reserveMovementCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = 'RESERVE'")
            .query(Integer.class)
            .single();
    }


}
