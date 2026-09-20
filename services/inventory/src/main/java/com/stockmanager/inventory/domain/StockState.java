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


}
