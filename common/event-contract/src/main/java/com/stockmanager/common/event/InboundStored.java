package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record InboundStored(String eventId, long inboundId, String locationCode,
                            List<Item> items, Instant occurredAt) {

    public record Item(long productId, int quantity) {

    }
}
