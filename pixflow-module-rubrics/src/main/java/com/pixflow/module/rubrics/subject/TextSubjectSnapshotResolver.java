package com.pixflow.module.rubrics.subject;

import com.pixflow.module.task.api.TaskOutcomeQuery;
import java.nio.charset.StandardCharsets;

/** 只通过 Task public outcome seam 解析 Copy 与 confirmed decision。 */
public final class TextSubjectSnapshotResolver {
    private final TaskOutcomeQuery query;

    public TextSubjectSnapshotResolver(TaskOutcomeQuery query) {
        this.query = query;
    }

    public CopyResultSubject resolveCopy(String subjectId) {
        long resultId = Long.parseLong(subjectId);
        var snapshot = query.successfulCopy(resultId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "successful copy result not found: " + subjectId));
        String canonical = String.join("\n",
                Long.toString(snapshot.resultId()),
                Long.toString(snapshot.taskId()),
                snapshot.text(),
                value(snapshot.producerProvider()),
                value(snapshot.producerModel()),
                snapshot.completedAt() == null ? "" : snapshot.completedAt().toString());
        return new CopyResultSubject(
                subjectId,
                snapshot.taskId(),
                snapshot.text(),
                snapshot.producerProvider(),
                snapshot.producerModel(),
                snapshot.completedAt(),
                hash(canonical));
    }

    public TaskDecisionSubject resolveDecision(String subjectId) {
        int separator = subjectId.indexOf('@');
        if (separator <= 0 || separator == subjectId.length() - 1) {
            throw new IllegalArgumentException(
                    "task decision subject must use taskId@revision identity");
        }
        long taskId = Long.parseLong(subjectId.substring(0, separator));
        String revision = subjectId.substring(separator + 1);
        var snapshot = query.confirmedDecision(taskId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "confirmed task decision not found: " + subjectId));
        String canonical = String.join("\n",
                Long.toString(snapshot.taskId()),
                snapshot.taskType(),
                value(snapshot.conversationId()),
                snapshot.packageId() == null ? "" : snapshot.packageId().toString(),
                snapshot.confirmedProposal(),
                snapshot.dagSnapshot(),
                snapshot.decisionRevision(),
                value(snapshot.schemaVersion()),
                snapshot.confirmedAt() == null ? "" : snapshot.confirmedAt().toString());
        return new TaskDecisionSubject(
                subjectId,
                taskId,
                revision,
                snapshot.taskType(),
                snapshot.conversationId(),
                snapshot.packageId(),
                snapshot.confirmedProposal(),
                snapshot.dagSnapshot(),
                snapshot.schemaVersion(),
                snapshot.confirmedAt(),
                hash(canonical));
    }

    private static String hash(String value) {
        return ImageSubjectSnapshotResolver.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
