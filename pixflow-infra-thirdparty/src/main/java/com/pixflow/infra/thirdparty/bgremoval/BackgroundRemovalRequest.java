package com.pixflow.infra.thirdparty.bgremoval;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

public record BackgroundRemovalRequest(
        byte[] image,
        String contentType,
        URI sourceUri,
        BackgroundRemovalOptions options) {

    public BackgroundRemovalRequest {
        options = options == null ? BackgroundRemovalOptions.defaults() : options;
        contentType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        if (image != null) {
            image = Arrays.copyOf(image, image.length);
        }
        if (image == null && sourceUri == null) {
            throw new IllegalArgumentException("image 和 sourceUri 至少需要一个");
        }
        Objects.requireNonNull(options, "options");
    }

    @Override
    public byte[] image() {
        return image == null ? null : Arrays.copyOf(image, image.length);
    }
}
