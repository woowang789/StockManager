package com.stockmanager.wms.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
class PendingTransferRetryJob {

    private static final Duration GRACE = Duration.ofSeconds(30);

    private final TransferService transferService;

    PendingTransferRetryJob(TransferService transferService) {
        this.transferService = transferService;
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    void confirmPendingTransfers() {
        transferService.confirmPending(Instant.now().minus(GRACE));
    }
}
