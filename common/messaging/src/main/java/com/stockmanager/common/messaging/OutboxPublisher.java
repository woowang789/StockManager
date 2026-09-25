package com.stockmanager.common.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxPublisher {

    public static final String EVENT_TYPE_HEADER = "event-type";

    private static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void publishPending() {
        for (OutboxMessage message : outboxRepository.findUnpublished(BATCH_SIZE)) {
            try {
                kafkaTemplate.send(toRecord(message)).join();
            } catch (RuntimeException exception) {
                log.warn("outbox {}번을 발행하지 못했습니다. 다음 차례에 다시 시도합니다: {}",message.id(), exception.toString());
                return;
            }
            outboxRepository.markPublished(message.id(), Instant.now());
        }
    }

    private ProducerRecord<String,String> toRecord(OutboxMessage message){
        return new ProducerRecord<>(message.topic(), null, message.messageKey(), message.payload(),
            List.of(new RecordHeader(EVENT_TYPE_HEADER, message.eventType().getBytes(StandardCharsets.UTF_8))));
    }
}
