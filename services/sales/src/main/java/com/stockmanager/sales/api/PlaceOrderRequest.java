package com.stockmanager.sales.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlaceOrderRequest(
    @NotBlank @Size(max = 50) String orderNo,
    @NotEmpty List<@Valid Item> items) {

    public record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
    }

}
