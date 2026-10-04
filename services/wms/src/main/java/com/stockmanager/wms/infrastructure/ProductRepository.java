package com.stockmanager.wms.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class ProductRepository {

    private final JdbcClient jdbcClient;

    ProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(long productId, String sku, String name) {
        jdbcClient.sql("""
                        INSERT INTO product (id, sku, name) VALUES (:productId, :sku, :name) AS incoming
                        ON DUPLICATE KEY UPDATE sku = incoming.sku, name = incoming.name
                """)
            .param("productId", productId)
            .param("sku", sku)
            .param("name", name)
            .update();
    }

    public List<Long> findUnregistered(List<Long> productIds) {
        Set<Long> registered = new HashSet<>(jdbcClient.sql("SELECT id FROM product WHERE id IN (:productIds)")
            .param("productIds", productIds)
            .query(Long.class)
            .list());
        return productIds.stream()
            .filter(productId -> !registered.contains(productId))
            .toList();
    }
}
