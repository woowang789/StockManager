package com.stockmanager.inventory.domain;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String locationCode, long productId, StockState state, int requested) {
        super("재고가 부족합니다: %s 상품 %d의 %s %d개".formatted(locationCode, productId, state, requested));
    }
}
