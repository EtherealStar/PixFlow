package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;
import java.time.Instant;

public record TaskDecisionSubject(
        String id,
        long taskId,
        String revision,
        String taskType,
        String conversationId,
        Long packageId,
        String dagSnapshot,
        String schemaVersion,
        Instant confirmedAt,
        String snapshotHash) implements EvaluationSubject {

    @Override
    public SubjectType type() {
        return SubjectType.TASK_DECISION;
    }
}
