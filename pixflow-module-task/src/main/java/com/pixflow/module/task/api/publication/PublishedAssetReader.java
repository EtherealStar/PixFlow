package com.pixflow.module.task.api.publication;

import com.pixflow.infra.storage.ObjectLocation;

/** Task 下载边界按 Published Asset identity 获取稳定内容位置。 */
public interface PublishedAssetReader {
  PublishedAssetContent require(String referenceKey);

  record PublishedAssetContent(
      long imageId, ObjectLocation location, String contentType, long size) { }
}
