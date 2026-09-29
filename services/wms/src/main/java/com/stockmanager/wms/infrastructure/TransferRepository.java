package com.stockmanager.wms.infrastructure;

import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
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
public class TransferRepository {

    private final JdbcClient jdbcClient;

    TransferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(String fromLocationCode, String toLocationCode, List<TransferLine> lines) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                    INSERT INTO transfer (from_location_code, to_location_code, status, created_at)
                    VALUES (:from, :to, :status, :createdAt)
                """)
            .param("from", fromLocationCode)
            .param("to", toLocationCode)
            .param("status", TransferStatus.PENDING.name())
            .param("createdAt", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
            .update(keyHolder);

        long transferId = keyHolder.getKey().longValue();
        for (TransferLine line : lines) {
            jdbcClient.sql("""
                        INSERT INTO transfer_item (transfer_id, product_id, quantity)
                        VALUES (:transferId, :productId, :quantity)
                    """)
                .param("transferId", transferId)
                .param("productId", line.productId())
                .param("quantity", line.quantity())
                .update();
        }
        return transferId;
    }

    public int changeStatus(long transferId, TransferStatus from, TransferStatus to) {
        return jdbcClient.sql("""
                    UPDATE transfer SET status = :to
                    WHERE id = :transferId AND status = :from
                """)
            .param("to", to.name())
            .param("from", from.name())
            .param("transferId", transferId)
            .update();
    }

    public Optional<Transfer> find(long transferId) {
        return jdbcClient.sql("""
                    SELECT id, from_location_code, to_location_code, status
                    FROM transfer WHERE id = :transferId
                """)
            .param("transferId", transferId)
            .query((rs, rowNum) -> new Transfer(rs.getLong("id"), rs.getString("from_location_code"),
                rs.getString("to_location_code"), TransferStatus.valueOf(rs.getString("status")),
                lines(transferId)))
            .optional();
    }

    public List<Long> findPendingBefore(Instant createdBefore) {
        return jdbcClient.sql("""
                    SELECT id FROM transfer
                    WHERE status = :status AND created_at < :createdBefore
                """)
            .param("status", TransferStatus.PENDING.name())
            .param("createdBefore", LocalDateTime.ofInstant(createdBefore, ZoneOffset.UTC))
            .query(Long.class)
            .list();
    }

    private List<TransferLine> lines(long transferId) {
        return jdbcClient.sql("""
                        SELECT product_id, quantity FROM transfer_item
                        WHERE transfer_id = :transferId
                        ORDER BY product_id
                """)
            .param("transferId", transferId)
            .query(TransferLine.class)
            .list();
    }
}
