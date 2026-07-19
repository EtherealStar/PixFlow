package com.pixflow.module.task.api.download;

/** 读取可下载 Task result 的 Published Asset identity，不暴露执行表或候选对象。 */
public interface PublishedTaskResultQuery {
  PublishedTaskResult require(long resultId);

  record PublishedTaskResult(long resultId, String referenceKey, String displayName) {
    public PublishedTaskResult {
      if (resultId <= 0) {
        throw new IllegalArgumentException("resultId must be positive");
      }
      if (referenceKey == null || referenceKey.isBlank()) {
        throw new IllegalArgumentException("referenceKey must not be blank");
      }
    }
  }
}
