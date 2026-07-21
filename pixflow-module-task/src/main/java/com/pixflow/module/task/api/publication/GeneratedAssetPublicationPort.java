package com.pixflow.module.task.api.publication;

import java.time.Instant;

/** Task 在 SUCCESS checkpoint 之后调用的 Generated Image 发布端口。 */
public interface GeneratedAssetPublicationPort {
  PublishedGeneratedAsset publish(GeneratedAssetCandidate candidate);

  void markTaskFinished(long taskId, Instant finishedAt);
}
