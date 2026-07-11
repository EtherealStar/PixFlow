package com.pixflow.infra.cache.tokenbucket;

import java.time.Duration;
import java.util.Objects;

public record TokenBucketDecision(boolean allowed, long remaining, Duration retryAfter) {
    public TokenBucketDecision {
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining 不能为负数");
        }
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter 不能为负数");
        }
    }
}
