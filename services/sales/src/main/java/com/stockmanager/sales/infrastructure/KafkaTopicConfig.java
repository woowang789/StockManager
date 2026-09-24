package com.stockmanager.sales.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    @Bean
    NewTopic salesOrderTopic() {
        return TopicBuilder.name(OrderEventPublisher.TOPIC).partitions(3)
            .replicas(1).build();
    }
}
