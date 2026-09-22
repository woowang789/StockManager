package com.stockmanager.sales.domain;

import jakarta.persistence.Embeddable;

/**
 * 주문 줄. 주문에 딸린 값이라 따로 식별자가 없다.
 */
@Embeddable
public record OrderLine(long productId, int quantity) {

    public OrderLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 1개 이상이어야 합니다");
        }
    }
}
