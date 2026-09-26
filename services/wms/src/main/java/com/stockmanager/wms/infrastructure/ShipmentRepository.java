package com.stockmanager.wms.infrastructure;

import com.stockmanager.wms.domain.Shipment;
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
import java.util.Optional;

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

    public int changeStatus(String orderNo, ShipmentStatus from, ShipmentStatus to) {
        return jdbcClient.sql("""
                    UPDATE shipment SET status = :to
                    WHERE order_no = :orderNo AND status = :from
                """)
            .param("to", to.name())
            .param("from", from.name())
            .param("orderNo", orderNo)
            .update();
    }

    public Optional<Shipment> find(String orderNo) {
        return jdbcClient.sql("SELECT order_no, location_code, status FROM shipment WHERE order_no = :orderNo")
            .param("orderNo", orderNo)
            .query((rs, rowNum) -> new Shipment(rs.getString("order_no"), rs.getString("location_code"),
                ShipmentStatus.valueOf(rs.getString("status")), lines(orderNo)))
            .optional();
    }

    private List<ShipmentLine> lines(String orderNo) {
        return jdbcClient.sql("""
                    SELECT i.product_id, i.quantity
                      FROM shipment_item i JOIN shipment s ON s.id = i.shipment_id
                      WHERE s.order_no = :orderNo
                      ORDER BY i.product_id
                """)
            .param("orderNo", orderNo)
            .query((rs, rowNum) -> new ShipmentLine(rs.getLong("product_id"), rs.getInt("quantity")))
            .list();
    }
}
