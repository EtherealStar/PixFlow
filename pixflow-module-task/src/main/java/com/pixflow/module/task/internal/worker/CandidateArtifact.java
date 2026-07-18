package com.pixflow.module.task.internal.worker;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.task.api.publication.CandidateKind;
import com.pixflow.module.task.api.publication.ProducerIdentity;
import com.pixflow.module.task.api.publication.SourceImageIdentity;
import java.util.LinkedHashSet;
import java.util.List;

/** 子线程返回给 owner thread 的不可变 TMP 候选描述。 */
public record CandidateArtifact(
    ObjectLocation location,
    long size,
    String contentType,
    String extension,
    CandidateKind kind,
    List<SourceImageIdentity> sourceImages,
    ProducerIdentity producer) {
  public CandidateArtifact {
    if (location == null || location.bucket() != BucketType.TMP) {
      throw new IllegalArgumentException("candidate must be in TMP");
    }
    if (size <= 0
        || contentType == null
        || contentType.isBlank()
        || extension == null
        || extension.isBlank()) {
      throw new IllegalArgumentException("candidate media metadata is incomplete");
    }
    if (kind == null || producer == null || producer.kind() != kind) {
      throw new IllegalArgumentException("candidate kind and producer must agree");
    }
    if (sourceImages == null || sourceImages.isEmpty()) {
      throw new IllegalArgumentException("candidate sources must not be empty");
    }
    sourceImages = List.copyOf(new LinkedHashSet<>(sourceImages));
  }
}
