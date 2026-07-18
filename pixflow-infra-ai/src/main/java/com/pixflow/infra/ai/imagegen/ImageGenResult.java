package com.pixflow.infra.ai.imagegen;

import com.pixflow.infra.ai.model.TokenUsage;
import java.util.Objects;

/**
 * 生图结果。
 */
public record ImageGenResult(byte[] image, String contentType, TokenUsage usage,
                             ImageProducer producer) {
    public ImageGenResult {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image must not be empty");
        }
        image = image.clone();
        contentType = Objects.requireNonNull(contentType, "contentType");
        usage = Objects.requireNonNull(usage, "usage");
        producer = Objects.requireNonNull(producer, "producer");
    }

    @Override
    public byte[] image() {
        return image.clone();
    }
}
