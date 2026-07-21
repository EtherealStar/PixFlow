package com.pixflow.module.rubrics.subject;

import com.pixflow.module.task.api.TaskOutcomeQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ImageSubjectSnapshotResolver {
    private final TaskOutcomeQuery query;

    public ImageSubjectSnapshotResolver(TaskOutcomeQuery query) {
        this.query = query;
    }

    public ImageResultSubject resolve(String subjectId) {
        long id = Long.parseLong(subjectId);
        var snapshot = query.successfulResult(id)
                .orElseThrow(() -> new IllegalArgumentException("successful image result not found: " + subjectId));
        String canonical = canonical(snapshot);
        return new ImageResultSubject(subjectId, snapshot.taskId(), snapshot.skuId(), snapshot.unitKind(),
                snapshot.imageId(), snapshot.groupKey(), snapshot.viewId(), snapshot.branchId(),
                snapshot.generatedImageId(), snapshot.referenceKey(), snapshot.bytesOut(),
                snapshot.producerProvider(), snapshot.producerModel(),
                sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String canonical(TaskOutcomeQuery.SuccessfulResultSnapshot snapshot) {
        return String.join("\n", Long.toString(snapshot.resultId()), Long.toString(snapshot.taskId()),
                value(snapshot.unitKind()), value(snapshot.imageId()), value(snapshot.skuId()),
                value(snapshot.groupKey()), value(snapshot.viewId()), value(snapshot.branchId()),
                Long.toString(snapshot.generatedImageId()), value(snapshot.referenceKey()),
                Long.toString(snapshot.bytesOut()),
                value(snapshot.producerProvider()), value(snapshot.producerModel()),
                snapshot.completedAt() == null ? "" : snapshot.completedAt().toString());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
