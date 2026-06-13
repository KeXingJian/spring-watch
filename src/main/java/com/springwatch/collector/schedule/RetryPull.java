package com.springwatch.collector.schedule;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPull(
        Long appid,
        String host,
        int attempt,
        Instant enqueueTime
) {

    private static final long BASE_BACKOFF_MS = 500L;
    private static final int MAX_SHIFT = 6;

    public long backoffMs() {
        if (attempt <= 0) {
            return 0L;
        }
        int shift = Math.min(attempt, MAX_SHIFT);
        long base = BASE_BACKOFF_MS << shift;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2));
        return base + jitter;
    }

    public RetryPull withIncrementedAttempt() {
        return new RetryPull(appid, host, attempt + 1, Instant.now());
    }
}
