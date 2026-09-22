package com.stockmanager.sales.domain;

/**
 * 주문 상태. 전이 규칙은 docs/ARCHITECTURE.md 6.2절에 있다.
 */
public enum OrderStatus {

    PENDING,
    RESERVED,
    REJECTED,
    SHIPPED,
    CANCEL_REQUESTED,
    CANCELED
}
