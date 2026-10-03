package com.stockmanager.wms.api;

import com.stockmanager.wms.domain.PutawayLine;

record PutawayResponse(long productId, int quantity, String binCode) {

    static PutawayResponse from(PutawayLine line) {
        return new PutawayResponse(line.productId(), line.quantity(), line.binCode());
    }
}
