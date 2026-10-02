package com.stockmanager.wms.domain;

import java.util.List;

public record Inbound(long id, String locationCode, InboundOrigin origin, InboundStatus status, List<InboundLine> lines) {
}
