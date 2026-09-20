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
}
