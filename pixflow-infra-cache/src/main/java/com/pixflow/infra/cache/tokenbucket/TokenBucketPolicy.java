package com.pixflow.infra.cache.tokenbucket;

import java.time.Duration;
import java.util.Objects;

public record TokenBucketPolicy(
        long capacity,
        long refillTokens,
        Duration refillPeriod,
        Duration idleTtl) {

    public TokenBucketPolicy {
        if (capacity <= 0 || refillTokens <= 0) {
            throw new IllegalArgumentException("capacity 和 refillTokens 必须大于 0");
        }
        requirePositive(refillPeriod, "refillPeriod");
        requirePositive(idleTtl, "idleTtl");
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
