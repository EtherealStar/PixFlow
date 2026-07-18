package com.pixflow.module.task.api.publication;

/** File 已发布的稳定图片身份，不包含对象存储位置。 */
public record PublishedGeneratedAsset(long imageId, String referenceKey) {
  public PublishedGeneratedAsset {
    if (imageId <= 0) {
      throw new IllegalArgumentException("imageId must be positive");
    }
    if (referenceKey == null || referenceKey.isBlank()) {
      throw new IllegalArgumentException("referenceKey must not be blank");
    }
    referenceKey = referenceKey.trim();
  }
}
