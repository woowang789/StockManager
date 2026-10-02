package com.stockmanager.wms.domain;

public final class Locations {

    public static final String CENTER = "DC";

    private Locations() {

    }

    public static boolean managesBins(String locationCode) {
        return CENTER.equals(locationCode);
    }
}
