package com.stockmanager.common.event;

import java.time.Instant;

public record OrderCancelRequested(String eventId, String orderNo, Instant occurredAt) {

}
