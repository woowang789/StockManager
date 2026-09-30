package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record TransferDispatched(String eventId, long transferId, String fromLocationCode, String toLocationCode,
                                 List<Item> items, Instant occurredAt) {
    public record Item(long productId, int quantity) {
    }
}
