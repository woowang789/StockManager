package com.stockmanager.product.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    @Bean
    NewTopic productTopic() {
        return TopicBuilder.name(ProductEventRecorder.TOPIC).partitions(3).replicas(1).build();
    }
}
