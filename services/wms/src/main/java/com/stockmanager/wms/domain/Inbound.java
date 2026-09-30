package com.stockmanager.wms.domain;

import java.util.List;

public record Inbound(long id, String locationCode, Long transferId, InboundStatus status, List<InboundLine> lines) {

    public boolean fromTransfer() {
        return transferId != null;
    }
}
