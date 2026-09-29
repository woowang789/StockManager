package com.stockmanager.wms.domain;

public enum TransferStatus {
    PENDING,
    REQUESTED,
    REJECTED,
    PICKED,
    IN_TRANSIT,
    RECEIVED,
    CANCELED
}
