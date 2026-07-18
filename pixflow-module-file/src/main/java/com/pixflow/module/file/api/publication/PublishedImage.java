package com.pixflow.module.file.api.publication;

import com.pixflow.infra.storage.ObjectLocation;

/** File READY Generated Image；stableObject 只供可信组合 adapter 使用。 */
public record PublishedImage(long imageId, String referenceKey, ObjectLocation stableObject) {
    public PublishedImage {
        if (imageId <= 0 || referenceKey == null || referenceKey.isBlank() || stableObject == null) {
            throw new IllegalArgumentException("published image is incomplete");
        }
    }
}
