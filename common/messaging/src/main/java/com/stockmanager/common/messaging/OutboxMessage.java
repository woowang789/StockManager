package com.stockmanager.common.messaging;

public record OutboxMessage(long id, String topic, String messageKey, String payload) {

}
