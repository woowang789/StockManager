package com.stockmanager.inventory.domain;

public record Stock(
        String locationCode,
        long productId,
        int inTransit,
        int putawayWait,
        int available,
        int reserved,
        int defective
) {

    public static Stock empty(String locationCode, long productId) {
        return new Stock(locationCode, productId, 0, 0, 0, 0, 0);
    }


}
