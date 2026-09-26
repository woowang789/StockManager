package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record ShipmentCanceled(String eventId, String orderNo, String locationCode, List<Item> items, boolean picked,
                               Instant occurredAt) {

    public record Item(long productId, int quantity) {
    }

}
