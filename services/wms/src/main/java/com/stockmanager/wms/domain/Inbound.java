package com.stockmanager.wms.domain;

import java.util.List;

public record Inbound(long id, String locationCode, InboundStatus status, List<InboundLine> lines) {
}
