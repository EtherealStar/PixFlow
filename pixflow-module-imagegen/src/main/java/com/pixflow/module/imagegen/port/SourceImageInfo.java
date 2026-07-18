package com.pixflow.module.imagegen.port;

import java.util.Objects;

/** Imagegen 校验源图时所需的最小事实，不携带对象存储位置。 */
public record SourceImageInfo(
        String imageId,
        String packageId,
        String contentType) {

    public SourceImageInfo {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(contentType, "contentType");
    }
}
