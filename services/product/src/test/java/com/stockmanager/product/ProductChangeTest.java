package com.stockmanager.product;

import com.stockmanager.common.web.ConcurrentUpdateException;
import com.stockmanager.product.application.ProductService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProductChangeTest {

    private static final int EDITORS = 10;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProductService productService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearProducts() {
        jdbcClient.sql("DELETE FROM product").update();
    }

    @Test
    @DisplayName("상품을 고치면 SKU와 이름이 바뀌고 version이 오른다")
    void changesProduct() throws Exception {
        long productId = productService.register("SKU-001", "무선 마우스").getId();

        change(productId, """
                {"sku":"SKU-001","name":"무선 마우스 블랙","version":0}
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/products/{productId}", productId))
            .andExpect(jsonPath("$.name").value("무선 마우스 블랙"))
            .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("읽은 뒤 다른 관리자가 먼저 고쳤으면 409로 거절한다")
    void rejectsStaleVersion() throws Exception {
        long productId = productService.register("SKU-002", "키보드").getId();
        change(productId, """
            {"sku":"SKU-002","name":"기계식 키보드","version":0}
            """)
            .andExpect(status().isOk());

        // A는 화면에 있던 옛 이름을 그대로 실어 SKU만 고치려 한다. 받으면 B의 이름이 되돌아간다
        change(productId, """
            {"sku":"SKU-002-A","name":"키보드","version":0}
            """)
            .andExpect(status().isConflict());

        mockMvc.perform(get("/products/{productId}", productId))
            .andExpect(jsonPath("$.sku").value("SKU-002"))
            .andExpect(jsonPath("$.name").value("기계식 키보드"));
    }

    @Test
    @DisplayName("다른 상품이 쓰는 SKU로는 고칠 수 없다")
    void rejectsDuplicateSku() throws Exception {
        productService.register("SKU-003", "모니터");
        long productId = productService.register("SKU-004", "모니터 받침대").getId();

        change(productId, """
                {"sku":"SKU-003","name":"모니터 받침대","version":0}
                """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("이미 등록된 SKU입니다: SKU-003"));
    }

    @Test
    @DisplayName("없는 상품을 고치면 404다")
    void returnsNotFoundForUnknownProduct() throws Exception{
        change(999, """
                {"sku":"SKU-999","name":"없는 상품","version":0}
                """)
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("같은 version으로 동시에 고치면 하나만 성공한다")
    void acceptsOneOfConcurrentChanges() throws Exception{
        long productId = productService.register("SKU-005", "헤드셋").getId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(EDITORS)) {
            for (int i = 0; i < EDITORS; i++) {
                String name = "헤드셋 " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        productService.change(productId, 0, "SKU-005", name);
                        return true;
                    } catch (ConcurrentUpdateException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
        }

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(versionOf(productId)).isEqualTo(1);
    }

    private ResultActions change(long productId, String body) throws Exception {
        return mockMvc.perform(put("/products/{productId}", productId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private long versionOf(long productId) {
        return jdbcClient.sql("SELECT version FROM product WHERE id = :productId")
            .param("productId", productId)
            .query(Long.class)
            .single();
    }
}
