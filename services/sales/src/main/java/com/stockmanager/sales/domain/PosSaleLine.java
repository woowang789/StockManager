package com.stockmanager.sales.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public record PosSaleLine(long productId, int quantity) {

    public PosSaleLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("판매 수량은 1개 이상이어야 합니다");
        }
    }
}
