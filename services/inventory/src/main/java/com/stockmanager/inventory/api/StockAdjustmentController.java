package com.stockmanager.inventory.api;

import com.stockmanager.inventory.application.StockAdjustmentService;
import com.stockmanager.inventory.domain.StockChange;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/adjustments")
public class StockAdjustmentController {

    private final StockAdjustmentService stockAdjustmentService;

    StockAdjustmentController(StockAdjustmentService stockAdjustmentService) {
        this.stockAdjustmentService = stockAdjustmentService;
    }

    @PostMapping
    public AdjustmentResponse adjust(
        @Valid @RequestBody AdjustmentRequest request,
        @RequestHeader(value = "X-User-Id", defaultValue = "unknown") String actor) {

        StockChange change = new StockChange(
            request.locationCode(), request.productId(), request.state(), request.delta());
        long movementId = stockAdjustmentService.adjust(change, request.reason(), actor);
        return new AdjustmentResponse(movementId);
    }
}
