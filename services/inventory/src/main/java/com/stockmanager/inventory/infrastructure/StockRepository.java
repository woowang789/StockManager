package com.stockmanager.inventory.infrastructure;

import com.stockmanager.inventory.domain.InsufficientStockException;
import com.stockmanager.inventory.domain.Stock;
import com.stockmanager.inventory.domain.StockState;
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

    public int increase(String locationCode, long productId, StockState state, int quantity) {
        String column = state.columnName();
        jdbcClient.sql("""
                INSERT INTO stock (location_code, product_id, %s)
                VALUES (:locationCode, :productId, :quantity)
                ON DUPLICATE KEY UPDATE %s = %s + :quantity
                """.formatted(column, column, column))
            .param("locationCode", locationCode)
            .param("productId", productId)
            .param("quantity", quantity)
            .update();
        return balanceOf(locationCode, productId, state);
    }

    public int decrease(String locationCode, long productId, StockState state, int quantity) {
        String column = state.columnName();
        int updated = jdbcClient.sql("""
                UPDATE stock
                SET %s = %s - :quantity
                WHERE location_code = :locationCode
                  AND product_id = :productId
                  AND %s >= :quantity
                """.formatted(column, column, column))
            .param("locationCode", locationCode)
            .param("productId", productId)
            .param("quantity", quantity)
            .update();
        if (updated == 0) {
            throw new InsufficientStockException(locationCode, productId, state, quantity);
        }
        return balanceOf(locationCode, productId, state);
    }

    private int balanceOf(String locationCode, long productId, StockState state) {
        return jdbcClient.sql("""
                SELECT %s FROM stock
                WHERE location_code = :locationCode AND product_id = :productId
                """.formatted(state.columnName()))
            .param("locationCode", locationCode)
            .param("productId", productId)
            .query(Integer.class)
            .single();
    }

}
