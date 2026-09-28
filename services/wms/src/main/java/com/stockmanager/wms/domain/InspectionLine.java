package com.stockmanager.wms.domain;

public record InspectionLine(long productId, int goodQuantity, int defectiveQuantity) {
}
