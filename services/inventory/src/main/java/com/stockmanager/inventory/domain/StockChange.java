package com.stockmanager.inventory.domain;

public record StockChange(String locationCode, long productId, StockState state, int delta) {

    public StockChange{
        if (delta == 0) {
            throw new IllegalArgumentException("변화량은 0일 수 없습니다.");
        }
    }
}
