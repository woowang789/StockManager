package com.stockmanager.inventory.infrastructure;

import com.stockmanager.inventory.domain.Stock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StockRepository {

    private static final String SELECT_STOCK = """
            SELECT location_code, product_id, in_transit, putaway_wait, available, reserved, defective
            FROM stock
            """;

    private final JdbcClient jdbcClient;

    StockRepository(JdbcClient jdbcClient){
        this.jdbcClient = jdbcClient;
    }

    public Optional<Stock> find(String locationCode, long productId) {
        return jdbcClient.sql(SELECT_STOCK + """
                        WHERE location_code = :locationCode AND product_id = :productId
                        """)
                .param("locationCode", locationCode)
                .param("productId", productId)
                .query(Stock.class)
                .optional();
    }

    public List<Stock> findByLocation(String locationCode) {
        return jdbcClient.sql(SELECT_STOCK + """
                          WHERE location_code = :locationCode 
                            ORDER BY product_id
                        """)
                .param("locationCode", locationCode)
                .query(Stock.class)
                .list();
    }

}
