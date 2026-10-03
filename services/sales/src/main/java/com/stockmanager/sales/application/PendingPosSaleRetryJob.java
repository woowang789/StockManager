package com.stockmanager.sales.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
class PendingPosSaleRetryJob {

    private static final Duration GRACE = Duration.ofSeconds(30);

    private final PosSaleService posSaleService;

    PendingPosSaleRetryJob(PosSaleService posSaleService) {
        this.posSaleService = posSaleService;
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    void confirmPendingSales() {
        posSaleService.confirmPending(Instant.now().minus(GRACE));
    }
}
