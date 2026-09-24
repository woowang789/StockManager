package com.stockmanager.common.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public class OutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    OutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void append(String topic, String messageKey, Object event) {
        jdbcClient.sql("""
                INSERT INTO outbox (topic, message_key, event_type, payload, created_at)
                VALUES (:topic, :messageKey, :eventType, :payload, :createdAt)
                """)
            .param("topic", topic)
            .param("messageKey", messageKey)
            .param("eventType", event.getClass().getSimpleName())
            .param("payload", objectMapper.writeValueAsString(event))
            .param("createdAt", utc(Instant.now()))
            .update();
    }

    public List<OutboxMessage> findUnpublished(int limit) {
        return jdbcClient.sql("""
                                SELECT id, topic, message_key, payload
                                  FROM outbox
                                WHERE published_at IS NULL
                                ORDER BY id
                                LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, rowNum) -> new OutboxMessage(
                rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"), rs.getString("payload")))
            .list();
    }

    public void markPublished(long id, Instant publishedAt) {
        jdbcClient.sql("UPDATE outbox SET published_at = :publishedAt WHERE id = :id")
            .param("publishedAt", utc(publishedAt))
            .param("id", id)
            .update();
    }


    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

}
