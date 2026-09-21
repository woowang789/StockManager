package com.stockmanager.inventory.domain;

/**
 * 예약의 생애. 지금은 ACTIVE만 쓰고, 9단계 운송에서 CONSUMED, 10단계 취소에서 RELEASED가 된다.
 */
public enum ReservationStatus {

    ACTIVE,
    CONSUMED,
    RELEASED
}
