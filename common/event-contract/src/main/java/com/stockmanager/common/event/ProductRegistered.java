package com.stockmanager.common.event;

import java.time.Instant;

public record ProductRegistered(String eventId, long productId, String sku, String name, Instant occurredAt) {
}
