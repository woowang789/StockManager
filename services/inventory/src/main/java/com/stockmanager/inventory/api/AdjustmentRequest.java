package com.stockmanager.inventory.api;

import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.StockState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdjustmentRequest(
    @NotBlank String locationCode,
    @NotNull Long productId,
    @NotNull StockState state,

    @NotNull Integer delta,
    @NotNull AdjustmentReason reason
) {
}
