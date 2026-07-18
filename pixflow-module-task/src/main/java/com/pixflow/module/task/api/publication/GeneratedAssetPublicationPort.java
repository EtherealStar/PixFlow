package com.pixflow.module.task.api.publication;

/** Task 在 SUCCESS checkpoint 之后调用的 Generated Image 发布端口。 */
public interface GeneratedAssetPublicationPort {
  PublishedGeneratedAsset publish(GeneratedAssetCandidate candidate);
}
