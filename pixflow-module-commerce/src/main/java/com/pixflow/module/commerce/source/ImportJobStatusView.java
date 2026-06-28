package com.pixflow.module.commerce.source;

import java.time.Instant;

public record ImportJobStatusView(
        Long id,
        String source,
        String platform,
        ImportJobStatus status,
        Integer skuCount,
        Integer succeededCount,
        Integer failedCount,
        String reportJson,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {
}
