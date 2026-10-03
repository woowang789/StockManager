package com.stockmanager.wms.domain;

import java.util.List;

public record Reputaway(long id, String locationCode, ReputawayOrigin origin, ReputawayStatus status,
                        List<ReputawayLine> lines) {
}
