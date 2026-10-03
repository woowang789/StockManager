package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record ReputawayStored(String eventId, long reputawayId, String locationCode, List<Item> items,
                              Instant occurredAt) {

    public record Item(long productId, int quantity) {

    }
}
