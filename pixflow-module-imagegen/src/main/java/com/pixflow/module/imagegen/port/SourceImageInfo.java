package com.pixflow.module.imagegen.port;

import java.util.Objects;

/** Imagegen 校验源图时所需的最小事实，不携带对象存储位置。 */
public record SourceImageInfo(
        String referenceKey,
        String contentType) {

    public SourceImageInfo {
        Objects.requireNonNull(referenceKey, "referenceKey");
        Objects.requireNonNull(contentType, "contentType");
    }
}
