package com.stockmanager.wms;

import com.stockmanager.wms.application.ProductBinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
public class ProductBinTest {

    @Autowired
    ProductBinService productBinService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM product_bin").update();
    }

    @Test
    @DisplayName("칸을 지정하고 바꿀 수 있다")
    void assignsAndReassignsBin() {
        productBinService.assign(1, "A-01-03");
        assertThat(binOf(1)).isEqualTo("A-01-03");

        productBinService.assign(1, "B-02-01");

        assertThat(binOf(1)).isEqualTo("B-02-01");
        assertThat(binCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 상품이 쓰는 칸은 지정할 수 없다")
    void rejectsBinTakenByAnotherProduct() {
        productBinService.assign(1, "A-01-03");

        assertThatThrownBy(() -> productBinService.assign(2, "A-01-03"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이미 다른 상품이 쓰는 칸입니다");
        assertThat(binOf(2)).isNull();
    }

    private String binOf(long productId) {
        return jdbcClient.sql("SELECT bin_code FROM product_bin WHERE location_code='DC' AND product_id=:p")
            .param("p", productId).query(String.class).optional().orElse(null);
    }

    private int binCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM product_bin").query(Integer.class).single();
    }
}
