package com.stockmanager.wms.infrastructure;

import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
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
public class InboundRepository {

    private final JdbcClient jdbcClient;

    InboundRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(String locationCode, Long transferId, List<InboundLine> lines) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO inbound (location_code, transfer_id ,status, created_at)
                        VALUES (:locationCode, :transferId ,:status, :createdAt)
                """)
            .param("locationCode", locationCode)
            .param("transferId", transferId)
            .param("status", InboundStatus.ARRIVED.name())
            .param("createdAt", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
            .update(keyHolder);

        long inboundId = keyHolder.getKey().longValue();
        for (InboundLine line : lines) {
            jdbcClient.sql("""
                            INSERT INTO inbound_item (inbound_id, product_id, expected_quantity)
                            VALUES (:inboundId, :productId, :expectedQuantity)
                    """)
                .param("inboundId", inboundId)
                .param("productId", line.productId())
                .param("expectedQuantity", line.expectedQuantity())
                .update();
        }
        return inboundId;
    }

    public int changeStatus(long inboundId, InboundStatus from, InboundStatus to) {
        return jdbcClient.sql("""
                    UPDATE inbound SET status = :to
                    WHERE id = :inboundId AND status = :from
                """)
            .param("to", to.name())
            .param("from", from.name())
            .param("inboundId", inboundId)
            .update();
    }

    public void recordInspection(long inboundId, List<InspectionLine> results) {
        for (InspectionLine result : results) {
            jdbcClient.sql("""
                        UPDATE inbound_item
                        SET good_quantity = :goodQuantity, defective_quantity = :defectiveQuantity
                        WHERE inbound_id = :inboundId AND product_id = :productId
                    """)
                .param("goodQuantity", result.goodQuantity())
                .param("defectiveQuantity", result.defectiveQuantity())
                .param("inboundId", inboundId)
                .param("productId", result.productId())
                .update();
        }
    }

    public Optional<Inbound> find (long inboundId){
        return jdbcClient.sql("SELECT id, location_code, transfer_id, status FROM inbound WHERE id = :inboundId")
            .param("inboundId", inboundId)
            .query((rs, rowNum) -> new Inbound(rs.getLong("id"), rs.getString("location_code"),
                rs.getObject("transfer_id", Long.class), InboundStatus.valueOf(rs.getString("status")), lines(inboundId)))
            .optional();
    }

    private List<InboundLine> lines(long inboundId) {
        return jdbcClient.sql("""
                    SELECT product_id, expected_quantity, good_quantity, defective_quantity
                            FROM inbound_item
                            WHERE inbound_id = :inboundId
                            ORDER BY product_id
                """)
            .param("inboundId", inboundId)
            .query(InboundLine.class)
            .list();
    }

}
