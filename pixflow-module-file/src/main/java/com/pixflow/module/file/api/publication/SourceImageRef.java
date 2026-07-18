package com.pixflow.module.file.api.publication;

/** Generated Image 的一个有序来源图片。 */
public record SourceImageRef(String imageId) {
    public SourceImageRef {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("imageId must not be blank");
        }
        imageId = imageId.trim();
    }
}
