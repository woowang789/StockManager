package com.stockmanager.wms.domain;

public record InboundLine(long productId, int expectedQuantity, Integer goodQuantity, Integer defectiveQuantity) {

    public static InboundLine expected(long productId, int expectedQuantity) {
        return new InboundLine(productId, expectedQuantity, null, null);
    }
}
