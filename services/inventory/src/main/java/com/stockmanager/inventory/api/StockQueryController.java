package com.stockmanager.inventory.api;

import com.stockmanager.inventory.application.StockQueryService;
import com.stockmanager.inventory.domain.Stock;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stocks")
public class StockQueryController {

    private final StockQueryService stockQueryService;

    StockQueryController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    @GetMapping("/{locationCode}/{productId}")
    public Stock get(@PathVariable String locationCode, @PathVariable long productId) {
        return stockQueryService.find(locationCode, productId);
    }

    @GetMapping
    public List<Stock> list(@RequestParam String locationCode){
        return stockQueryService.findByLocation(locationCode);
    }
}
