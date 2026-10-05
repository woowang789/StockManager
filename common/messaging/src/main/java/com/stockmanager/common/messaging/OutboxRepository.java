package com.stockmanager.common.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OutboxRepository {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    OutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,Tracer tracer, Propagator propagator) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void append(String topic, String messageKey, Object event) {
        jdbcClient.sql("""
                INSERT INTO outbox (topic, message_key, event_type, payload,trace_headers, created_at)
                VALUES (:topic, :messageKey, :eventType, :payload, :traceHeaders, :createdAt)
                """)
            .param("topic", topic)
            .param("messageKey", messageKey)
            .param("eventType", event.getClass().getSimpleName())
            .param("payload", objectMapper.writeValueAsString(event))
            .param("traceHeaders", traceHeaders())
            .param("createdAt", utc(Instant.now()))
            .update();
    }

    public List<OutboxMessage> findUnpublished(int limit) {
        return jdbcClient.sql("""
                                SELECT id, topic, message_key,event_type, payload,trace_headers
                                  FROM outbox
                                WHERE published_at IS NULL
                                ORDER BY id
                                LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, rowNum) -> new OutboxMessage(
                rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"),
                rs.getString("event_type"),rs.getString("payload"), headersOf(rs.getString("trace_headers"))))
            .list();
    }

    public void markPublished(long id, Instant publishedAt) {
        jdbcClient.sql("UPDATE outbox SET published_at = :publishedAt WHERE id = :id")
            .param("publishedAt", utc(publishedAt))
            .param("id", id)
            .update();
    }

    private String traceHeaders() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        return objectMapper.writeValueAsString(headers);
    }

    private Map<String, String> headersOf(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, HEADERS);
    }


    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

}
