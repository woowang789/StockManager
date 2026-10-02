package com.stockmanager.wms.domain;

public sealed interface InboundOrigin {

    record Supplier() implements InboundOrigin {
    }

    record Transfer(long transferId) implements InboundOrigin {
    }

    static InboundOrigin ofTransferId(Long transferId) {
        return transferId == null ? new Supplier() : new Transfer(transferId);
    }

    default Long transferIdOrNull() {
        return switch (this) {
            case Supplier() -> null;
            case Transfer(long transferId) -> transferId;
        };
    }
}
