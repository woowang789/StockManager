package com.stockmanager.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record ReservationRequest(
    @NotBlank String refType,
    @NotBlank String refId,
    @NotBlank String locationCode,
    @NotEmpty @Valid List<Item> items
    ) {

    public record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
    }

}
