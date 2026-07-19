package com.pixflow.module.file.api.publication;

/** File READY Generated Image identity；稳定对象位置始终留在 File 内部。 */
public record PublishedImage(long imageId, String referenceKey) {
    public PublishedImage {
        if (imageId <= 0 || referenceKey == null || referenceKey.isBlank()) {
            throw new IllegalArgumentException("published image is incomplete");
        }
    }
}
