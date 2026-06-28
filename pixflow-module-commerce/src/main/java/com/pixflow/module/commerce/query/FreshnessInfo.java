package com.pixflow.module.commerce.query;

import java.time.Instant;

public record FreshnessInfo(
        boolean stale,
        Instant fetchedAt,
        String source,
        String reason) {
}
