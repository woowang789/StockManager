package com.stockmanager.inventory.domain;

public enum StockState {

    IN_TRANSIT,
    PUTAWAY_WAIT,
    AVAILABLE,
    RESERVED,
    DEFECTIVE;

    public String columnName() {
        return name().toLowerCase();
    }

    public static StockState forReceivedGoods(String locationCode) {
        return "STORE".equals(locationCode) ? AVAILABLE : PUTAWAY_WAIT;
    }

}
