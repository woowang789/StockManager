package com.stockmanager.wms.infrastructure;

import com.stockmanager.wms.domain.Reputaway;
import com.stockmanager.wms.domain.ReputawayLine;
import com.stockmanager.wms.domain.ReputawayOrigin;
import com.stockmanager.wms.domain.ReputawayStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class ReputawayRepository {

    private final JdbcClient jdbcClient;

    ReputawayRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(String locationCode, ReputawayOrigin origin, List<ReputawayLine> lines) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                    INSERT INTO reputaway (location_code, order_no, transfer_id, status, created_at)
                    VALUES (:locationCode, :orderNo, :transferId, :status, :createdAt)
                """)
            .param("locationCode", locationCode)
            .param("orderNo", origin.orderNoOrNull())
            .param("transferId", origin.transferIdOrNull())
            .param("status", ReputawayStatus.READY.name())
            .param("createdAt", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
            .update(keyHolder);

        long reputawayId = keyHolder.getKey().longValue();
        for (ReputawayLine line : lines) {
            jdbcClient.sql("""
                        INSERT INTO reputaway_item (reputaway_id, product_id, quantity)
                        VALUES (:reputawayId, :productId, :quantity)
                    """)
                .param("reputawayId", reputawayId)
                .param("productId", line.productId())
                .param("quantity", line.quantity())
                .update();
        }
        return reputawayId;
    }

    public int changeStatus(long reputawayId, ReputawayStatus from, ReputawayStatus to) {
        return jdbcClient.sql("""
                        UPDATE reputaway SET status = :to
                        WHERE id = :reputawayId AND status = :from
                """)
            .param("to", to.name())
            .param("from", from.name())
            .param("reputawayId", reputawayId)
            .update();
    }

    public Optional<Reputaway> find(long reputawayId) {
        return jdbcClient.sql("""
                        SELECT id, location_code, order_no, transfer_id, status
                        FROM reputaway WHERE id = :reputawayId
                """)
            .param("reputawayId", reputawayId)
            .query(this::toReputaway)
            .optional();
    }

    public List<Reputaway> findReady() {
        return jdbcClient.sql("""
                        SELECT id, location_code, order_no, transfer_id, status
                        FROM reputaway WHERE status = :status
                        ORDER BY id
                """)
            .param("status", ReputawayStatus.READY.name())
            .query(this::toReputaway)
            .list();
    }

    private Reputaway toReputaway(ResultSet rs, int rowNum) throws SQLException {
        long reputawayId = rs.getLong("id");
        return new Reputaway(reputawayId, rs.getString("location_code"),
            ReputawayOrigin.of(rs.getString("order_no"), rs.getObject("transfer_id", Long.class)),
            ReputawayStatus.valueOf(rs.getString("status")),
            lines(reputawayId));
    }

    private List<ReputawayLine> lines(long reputawayId) {
        return jdbcClient.sql("""
                        SELECT product_id, quantity FROM reputaway_item
                        WHERE reputaway_id = :reputawayId
                        ORDER BY product_id
                """)
            .param("reputawayId", reputawayId)
            .query(ReputawayLine.class)
            .list();
    }

}
