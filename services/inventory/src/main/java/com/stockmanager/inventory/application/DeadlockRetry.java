package com.stockmanager.inventory.application;

import org.springframework.dao.CannotAcquireLockException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

final class DeadlockRetry {

    private static final int MAX_ATTEMPTS = 5;

    private DeadlockRetry(){}

    static <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (CannotAcquireLockException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                backOff(attempt);
            }
        }
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(
                    5L * attempt, 15L * attempt)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
