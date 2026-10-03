package com.stockmanager.wms.domain;

public sealed interface ReputawayOrigin {

    record Shipment(String orderNo) implements ReputawayOrigin {
    }

    record Transfer(long transferId) implements ReputawayOrigin {
    }

    static ReputawayOrigin of(String orderNo, Long transferId) {
        return orderNo != null ? new Shipment(orderNo) : new Transfer(transferId);
    }

    default String orderNoOrNull() {
        return switch (this) {
            case Shipment(String orderNo) -> orderNo;
            case Transfer(long transferId) -> null;
        };
    }

    default Long transferIdOrNull() {
        return switch (this) {
            case Shipment(String orderNo) -> null;
            case Transfer(long transferId) -> transferId;
        };
    }
}
