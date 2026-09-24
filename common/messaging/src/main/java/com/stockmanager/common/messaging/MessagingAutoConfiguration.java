package com.stockmanager.common.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
public class MessagingAutoConfiguration {

    @Bean
    OutboxRepository outboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new OutboxRepository(jdbcClient, objectMapper);
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        return new OutboxPublisher(outboxRepository, kafkaTemplate);
    }
}
