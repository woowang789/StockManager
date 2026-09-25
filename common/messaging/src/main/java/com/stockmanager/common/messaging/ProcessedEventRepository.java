package com.stockmanager.common.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class ProcessedEventRepository {

    private final JdbcClient jdbcClient;

    ProcessedEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(String eventId) {
        jdbcClient.sql("INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt)")
            .param("eventId", eventId)
            .param("processedAt", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
            .update();
    }
}
