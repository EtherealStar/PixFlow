package com.pixflow.module.task.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskOutcomeQuery {
  Optional<SuccessfulResultSnapshot> successfulResult(long resultId);

  List<SuccessfulResultSnapshot> successfulResults(long taskId);

  Optional<CopyResultSnapshot> successfulCopy(long resultId);

  Optional<ConfirmedDecisionSnapshot> confirmedDecision(long taskId, String revision);

  record SuccessfulResultSnapshot(
      long resultId,
      long taskId,
      String unitKind,
      String imageId,
      String skuId,
      String groupKey,
      String viewId,
      String branchId,
      long generatedImageId,
      String referenceKey,
      long bytesOut,
      Instant completedAt) { }

  record CopyResultSnapshot(
      long resultId,
      long taskId,
      String text,
      String producerProvider,
      String producerModel,
      Instant completedAt) { }

  record ConfirmedDecisionSnapshot(
      long taskId,
      String taskType,
      String conversationId,
      Long packageId,
      String dagSnapshot,
      String decisionRevision,
      String schemaVersion,
      Instant confirmedAt) { }
}
