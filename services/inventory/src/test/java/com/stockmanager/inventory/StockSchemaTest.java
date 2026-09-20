package com.stockmanager.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StockSchemaTest {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("재고 수량은 음수가 될 수 없다")
    void rejectsNegativeQuantity() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO stock (location_code, product_id, available)
                VALUES ('DC',1,-1)
                """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_available");
    }

    @Test
    @DisplayName("거점은 STORE와 DC만 허용한다.")
    void rejectsUnknownLocation(){
        assertThatThrownBy(() -> jdbcClient.sql("""
                    INSERT INTO stock(location_code, product_id)
                    VALUES ('HQ', 1)
                """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_location");
    }
}
