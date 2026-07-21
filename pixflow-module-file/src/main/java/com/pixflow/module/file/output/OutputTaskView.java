package com.pixflow.module.file.output;

import com.pixflow.module.file.api.publication.OutputTaskType;
import java.time.Instant;

public record OutputTaskView(
        String taskId,
        OutputTaskType taskType,
        long generatedImageCount,
        Instant createdAt,
        Instant finishedAt) {
}
