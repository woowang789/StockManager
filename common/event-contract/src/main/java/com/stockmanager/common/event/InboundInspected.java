package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record InboundInspected(String eventId, long inboundId, String locationCode,
                               List<Item> items, boolean putawayPending,Instant occurredAt) {

    public record Item(long productId, int goodQuantity, int defectiveQuantity) {
    }

}
