package com.stockmanager.wms.domain;

import java.util.List;

public record Transfer(long id, String fromLocationCode, String toLocationCode, TransferStatus status,
                       List<TransferLine> lines) {
}
