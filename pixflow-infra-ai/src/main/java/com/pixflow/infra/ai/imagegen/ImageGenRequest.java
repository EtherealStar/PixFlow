package com.pixflow.infra.ai.imagegen;

import com.pixflow.infra.ai.model.ChatOptions;
import java.util.Objects;

/**
 * 源图重绘请求。
 */
public record ImageGenRequest(byte[] sourceImage, String sourceContentType, String prompt, ChatOptions options) {
    public ImageGenRequest {
        if (sourceImage == null || sourceImage.length == 0) {
            throw new IllegalArgumentException("sourceImage must not be empty");
        }
        sourceImage = sourceImage.clone();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        sourceContentType = Objects.requireNonNull(sourceContentType, "sourceContentType");
    }

    @Override
    public byte[] sourceImage() {
        return sourceImage.clone();
    }
}
