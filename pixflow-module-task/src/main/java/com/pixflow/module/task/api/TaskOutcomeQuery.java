package com.pixflow.module.task.api;

import com.pixflow.infra.storage.ObjectLocation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskOutcomeQuery {
    Optional<SuccessfulResultSnapshot> successfulResult(long resultId);

    List<SuccessfulResultSnapshot> successfulResults(long taskId);

    record SuccessfulResultSnapshot(
            long resultId,
            long taskId,
            String unitKind,
            String imageId,
            String skuId,
            String groupKey,
            String viewId,
            String branchId,
            ObjectLocation output,
            long bytesOut,
            Instant completedAt) {
    }
}
