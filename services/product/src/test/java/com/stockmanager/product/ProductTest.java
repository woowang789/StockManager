package com.stockmanager.product;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProductTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearProducts() {
        jdbcClient.sql("DELETE FROM product").update();
    }

    @Test
    @DisplayName("상품을 등록하면 번호가 매겨지고, 그 번호로 조회된다")
    void registersAndFinds() throws Exception {
        register("""
                {"sku":"SKU-001","name":"무선 마우스"}
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.productId").value(idOf("SKU-001")));

        mockMvc.perform(get("/products/{productId}", idOf("SKU-001")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sku").value("SKU-001"))
            .andExpect(jsonPath("$.name").value("무선 마우스"));
    }

    @Test
    @DisplayName("같은 SKU는 다시 등록할 수 없다")
    void rejectsDuplicateSku() throws Exception{
        register("""
                {"sku":"SKU-002","name":"키보드"}
                """)
            .andExpect(status().isOk());

        register("""
                {"sku":"SKU-002","name":"다른 키보드"}
                """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("이미 등록된 SKU입니다: SKU-002"));
        assertThat(productCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKU나 이름이 비어 있으면 등록할 수 없다")
    void rejectsBlankFields() throws Exception {
        register("""
                {"sku":"","name":"이름만 있다"}
                """)
            .andExpect(status().isBadRequest());
        register("""
                {"sku":"SKU-003"}
                """)
            .andExpect(status().isBadRequest());

        assertThat(productCount()).isZero();
    }

    @Test
    @DisplayName("없는 상품을 조회하면 404다")
    void returnsNotFoundForUnknownProduct() throws Exception {
        mockMvc.perform(get("/products/{productId}", 999))
            .andExpect(status().isNotFound());
    }


    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/products")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private long idOf(String sku) {
        return jdbcClient.sql("SELECT id FROM product WHERE sku = ?")
            .param(sku)
            .query(Long.class)
            .single();
    }

    private int productCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM product")
            .query(Integer.class)
            .single();
    }
}
