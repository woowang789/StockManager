package com.stockmanager.wms.domain;

import java.util.List;

public record Shipment(String orderNo, String locationCode, ShipmentStatus status, List<ShipmentLine> lines) {
}
