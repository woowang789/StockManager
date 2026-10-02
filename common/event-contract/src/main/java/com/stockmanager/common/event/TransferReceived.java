package com.stockmanager.common.event;

import java.time.Instant;
import java.util.List;

public record TransferReceived(String eventId, long transferId, String toLocationCode, List<Item> items,
                              boolean putawayPending ,Instant occurredAt) {

    public record Item(long productId, int goodQuantity, int defectiveQuantity) {
    }

}
