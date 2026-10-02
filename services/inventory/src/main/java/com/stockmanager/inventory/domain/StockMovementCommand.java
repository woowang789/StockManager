package com.stockmanager.inventory.domain;

import java.util.List;

public record StockMovementCommand(
    MovementType type,
    String refType,
    String refId,
    String reason,
    String actor,
    List<StockChange> changes
) {

    public String idempotencyKey() {
        if (refType == null) {
            return null;
        }
        return idempotencyKey(refType, refId, type);
    }

    public static String idempotencyKey(String refType, String refId, MovementType type) {
        return refType + ":" + refId + ":" + type.name();
    }
}
