package com.stockmanager.inventory.application;

import com.stockmanager.inventory.domain.Stock;
import com.stockmanager.inventory.infrastructure.StockRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockQueryService {

    private final StockRepository stockRepository;

    StockQueryService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public Stock find(String locationCode, long productId) {
        return stockRepository.find(locationCode, productId)
                .orElseGet(() -> Stock.empty(locationCode,productId));
    }

    public List<Stock> findByLocation(String locationCode) {
        return stockRepository.findByLocation(locationCode);
    }
}
