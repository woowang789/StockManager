package com.stockmanager.inventory.infrastructure;

import com.stockmanager.inventory.domain.ReservationStatus;
import com.stockmanager.inventory.domain.ReserveCommand;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcClient jdbcClient;

    ReservationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(ReserveCommand command, ReserveCommand.Item item) {
        jdbcClient.sql("""
                    INSERT INTO reservation (ref_type, ref_id, location_code,product_id, quantity, status)
                    VALUES (:refType, :refId, :locationCode, :productId, :quantity, :status)
                """)
            .param("refType", command.refType())
            .param("refId", command.refId())
            .param("locationCode", command.locationCode())
            .param("productId", item.productId())
            .param("quantity", item.quantity())
            .param("status", ReservationStatus.ACTIVE.name())
            .update();
    }
}
