package com.pixflow.module.task.api.publication;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.Instant;

/** Task 交给 File 的完整候选描述。 */
public record GeneratedAssetCandidate(
    long taskId,
    String conversationId,
    String taskType,
    Instant taskCreatedAt,
    long resultId,
    String unitKey,
    long resultRunEpoch,
    long packageId,
    ObjectLocation candidate,
    long size,
    String contentType,
    String extension,
    CandidateKind kind,
    List<SourceImageIdentity> sourceImages,
    ProducerIdentity producer) {
  public GeneratedAssetCandidate {
    if (taskId <= 0 || resultId <= 0 || resultRunEpoch <= 0 || packageId <= 0 || size <= 0) {
      throw new IllegalArgumentException("ids, epoch and size must be positive");
    }
    conversationId = requireText(conversationId, "conversationId");
    taskType = requireText(taskType, "taskType");
    if (taskCreatedAt == null) {
      throw new IllegalArgumentException("taskCreatedAt must not be null");
    }
    unitKey = requireText(unitKey, "unitKey");
    if (candidate == null || candidate.bucket() != BucketType.TMP) {
      throw new IllegalArgumentException("candidate must be in TMP");
    }
    contentType = requireText(contentType, "contentType");
    extension = requireText(extension, "extension");
    if (kind == null || producer == null || producer.kind() != kind) {
      throw new IllegalArgumentException("candidate kind and producer must agree");
    }
    if (sourceImages == null || sourceImages.isEmpty()) {
      throw new IllegalArgumentException("sourceImages must not be empty");
    }
    sourceImages = List.copyOf(new LinkedHashSet<>(sourceImages));
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
