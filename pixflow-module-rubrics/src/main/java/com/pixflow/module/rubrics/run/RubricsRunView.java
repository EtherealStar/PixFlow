package com.pixflow.module.rubrics.run;

import java.time.Instant;

public record RubricsRunView(
        Long id,
        String templateId,
        String templateVersion,
        RunTriggerType triggerType,
        RunStatus status,
        int totalCount,
        int succeededCount,
        int isolatedCount,
        int failedCount,
        Instant createdAt,
        Instant finishedAt) {
}
