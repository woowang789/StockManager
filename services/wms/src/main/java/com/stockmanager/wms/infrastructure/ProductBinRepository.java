package com.stockmanager.wms.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ProductBinRepository {

    private final JdbcClient jdbcClient;

    ProductBinRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void assign(String locationCode, long productId, String binCode) {
        int updated = jdbcClient.sql("""
                    UPDATE product_bin SET bin_code = :binCode
                    WHERE location_code = :locationCode AND product_id = :productId
                """)
            .param("binCode", binCode)
            .param("locationCode", locationCode)
            .param("productId", productId)
            .update();
        if (updated == 1) {
            return;
        }
        jdbcClient.sql("""
                    INSERT INTO product_bin (location_code, product_id, bin_code)
                    VALUES (:locationCode, :productId, :binCode)
                """)
            .param("locationCode", locationCode)
            .param("productId", productId)
            .param("binCode", binCode)
            .update();
    }

    public Optional<String> find(String locationCode, long productId) {
        return jdbcClient.sql("""
                    SELECT bin_code FROM product_bin
                    WHERE location_code = :locationCode And product_id = :productId
                """)
            .param("locationCode", locationCode)
            .param("productId", productId)
            .query(String.class)
            .optional();
    }

    public Map<Long, String> findAll(String locationCode) {
        return jdbcClient.sql("SELECT product_id, bin_code FROM product_bin WHERE location_code = :locationCode")
            .param("locationCode", locationCode)
            .query((rs, rowNum) -> Map.entry(rs.getLong("product_id"), rs.getString("bin_code")))
            .list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
