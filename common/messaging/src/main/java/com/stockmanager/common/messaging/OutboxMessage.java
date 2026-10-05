package com.stockmanager.common.messaging;

import java.util.Map;

public record OutboxMessage(long id, String topic, String messageKey, String eventType, String payload,
                            Map<String,String> traceHeaders) {


}
