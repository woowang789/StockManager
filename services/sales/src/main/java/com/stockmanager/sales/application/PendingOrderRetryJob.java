package com.stockmanager.sales.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class PendingOrderRetryJob {

    private static final Duration GRACE = Duration.ofSeconds(30);

    private final OrderService orderService;

    PendingOrderRetryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    void confirmPendingOrders() {
        orderService.confirmPending(Instant.now().minus(GRACE));
    }
}
