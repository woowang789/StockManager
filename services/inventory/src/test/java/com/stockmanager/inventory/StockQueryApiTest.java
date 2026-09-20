package com.stockmanager.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockQueryApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearStock() {
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("거점과 상품으로 재고를 조회한다")
    void findsStock() throws Exception {
        jdbcClient.sql("""
                INSERT INTO stock (location_code, product_id, available, reserved)
                VALUES ('DC', 1, 7, 3)
                """).update();

        mockMvc.perform(get("/stocks/DC/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationCode").value("DC"))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.reserved").value(3));
    }

    @Test
    @DisplayName("재고 행이 없으면 수량이 모두 0이다")
    void returnZeroWhenRowIsMissing() throws Exception {
        mockMvc.perform(get("/stocks/DC/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    @DisplayName("거점의 재고 목록을 상품 순서로 조회한다")
    void listsStockOfLocation() throws Exception {
        jdbcClient.sql("""
                    INSERT INTO stock (location_code, product_id, available)
                    VALUES ('DC',2,5), ('DC',1,7), ('STORE',1,2)
                """).update();

        mockMvc.perform(get("/stocks").param("locationCode", "DC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[1].productId").value(2));
    }
}
