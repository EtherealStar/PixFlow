package com.pixflow.module.task.api.publication;

import com.pixflow.infra.storage.ObjectLocation;
import java.util.Optional;

/** Task 下载边界按 Published Asset identity 获取稳定内容位置。 */
public interface PublishedAssetReader {
  Optional<PublishedAssetContent> find(String referenceKey);

  default PublishedAssetContent require(String referenceKey) {
    return find(referenceKey)
        .orElseThrow(() -> new IllegalArgumentException("published asset not found"));
  }

  record PublishedAssetContent(
      long imageId, ObjectLocation location, String contentType, long size) { }
}
