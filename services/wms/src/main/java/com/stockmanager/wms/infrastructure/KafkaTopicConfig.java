package com.stockmanager.wms.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    @Bean
    NewTopic wmsShipmentTopic() {
        return TopicBuilder.name(ShipmentEventRecorder.TOPIC).partitions(3).replicas(1)
            .build();
    }

    @Bean
    NewTopic wmsInboundTopic() {
        return TopicBuilder.name(InboundEventRecorder.TOPIC).partitions(3).replicas(1)
            .build();
    }

    @Bean
    NewTopic wmsTransferTopic() {
        return TopicBuilder.name(TransferEventRecorder.TOPIC).partitions(3).replicas(1).build();
    }
}
