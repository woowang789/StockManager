package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record TransferCanceled(String eventId, long transferId, String fromLocationCode, List<Item> items,
                               List<Item> shortages, boolean putawayPending, Instant occurredAt) {
    public record Item(long productId, int quantity) {

    }
}
