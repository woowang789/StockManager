package com.stockmanager.wms.infrastructure;

import com.stockmanager.wms.domain.ShipmentLine;
import com.stockmanager.wms.domain.ShipmentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class ShipmentRepository {

    private final JdbcClient jdbcClient;

    ShipmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(String orderNo, String locationCode, List<ShipmentLine> lines) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO shipment (order_no, location_code, status, created_at)
                        VALUES (:orderNo, :locationCode, :status, :createdAt)
                """)
            .param("orderNo", orderNo)
            .param("locationCode", locationCode)
            .param("status", ShipmentStatus.READY.name())
            .param("createdAt", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
            .update(keyHolder);

        long shipmentId = keyHolder.getKey().longValue();
        for (ShipmentLine line : lines) {
            jdbcClient.sql("""
                        INSERT INTO shipment_item (shipment_id, product_id, quantity)
                        VALUES (:shipmentId, :productId, :quantity)
                    """)
                .param("shipmentId", shipmentId)
                .param("productId", line.productId())
                .param("quantity", line.quantity())
                .update();
        }
        return shipmentId;
    }
}
