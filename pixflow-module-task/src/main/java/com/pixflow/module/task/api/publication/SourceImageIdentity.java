package com.pixflow.module.task.api.publication;

/** Generated Image 的一个有序来源图片身份。 */
public record SourceImageIdentity(String imageId) {
  public SourceImageIdentity {
    if (imageId == null || imageId.isBlank()) {
      throw new IllegalArgumentException("imageId must not be blank");
    }
    imageId = imageId.trim();
  }
}
