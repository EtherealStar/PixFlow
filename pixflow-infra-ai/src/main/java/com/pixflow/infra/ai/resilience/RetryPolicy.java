package com.pixflow.infra.ai.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 退避策略。
 */
public record RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay, double jitterRatio) {
    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        if (baseDelay == null || maxDelay == null) {
            throw new IllegalArgumentException("delay must not be null");
        }
        if (jitterRatio < 0.0d || jitterRatio > 1.0d) {
            throw new IllegalArgumentException("jitterRatio must be in [0,1]");
        }
    }

    public Duration delayForAttempt(int retryIndex, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter;
        }
        long baseMillis = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * (1L << Math.max(0, retryIndex - 1)));
        long jitter = (long) (baseMillis * jitterRatio * ThreadLocalRandom.current().nextDouble(-1.0d, 1.0d));
        long millis = Math.max(0L, Math.min(maxDelay.toMillis(), baseMillis + jitter));
        return Duration.ofMillis(millis);
    }
}
