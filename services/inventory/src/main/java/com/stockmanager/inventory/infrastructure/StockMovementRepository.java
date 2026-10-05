package com.stockmanager.inventory.infrastructure;

import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockMovementCommand;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
public class StockMovementRepository {

    private final JdbcClient jdbcClient;
    private final Tracer tracer;

    StockMovementRepository(JdbcClient jdbcClient, Tracer tracer) {
        this.jdbcClient = jdbcClient;
        this.tracer = tracer;
    }

    public long insertMovement(StockMovementCommand command, Instant occurredAt, Instant recordedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                 INSERT INTO stock_movement(type, ref_type, ref_id, idempotency_key , reason, actor, trace_id,
                                            occurred_at, recorded_at)
                 VALUES (:type, :refType, :refId,:idempotencyKey, :reason, :actor, :traceId,
                            :occurredAt, :recordedAt)
                """)
            .param("type", command.type().name())
            .param("refType", command.refType())
            .param("refId", command.refId())
            .param("idempotencyKey", command.idempotencyKey())
            .param("reason", command.reason())
            .param("actor", command.actor())
            .param("traceId", currentTraceId())
            .param("occurredAt", utc(occurredAt))
            .param("recordedAt", utc(recordedAt))
            .update(keyHolder);

        return keyHolder.getKey().longValue();
    }

    public Optional<Long> findIdByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT id FROM stock_movement WHERE idempotency_key = :idempotencyKey")
            .param("idempotencyKey", idempotencyKey)
            .query(Long.class)
            .optional();
    }

    public List<StockChange> findChanges(String idempotencyKey) {
        return jdbcClient.sql("""
                    SELECT e.location_code, e.product_id, e.state, e.delta
                    FROM stock_entry e JOIN stock_movement m ON m.id = e.movement_id
                    WHERE m.idempotency_key = :idempotencyKey
                """)
            .param("idempotencyKey", idempotencyKey)
            .query(StockChange.class)
            .list();
    }

    public void insertEntry(long movementId, StockChange change, int balanceAfter) {
        jdbcClient.sql("""
                INSERT INTO stock_entry(movement_id, location_code, product_id, state, delta, balance_after)
                VALUES (:movementId, :locationCode, :productId, :state, :delta, :balanceAfter)
                """)
            .param("movementId", movementId)
            .param("locationCode", change.locationCode())
            .param("productId", change.productId())
            .param("state", change.state().name())
            .param("delta", change.delta())
            .param("balanceAfter", balanceAfter)
            .update();
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
