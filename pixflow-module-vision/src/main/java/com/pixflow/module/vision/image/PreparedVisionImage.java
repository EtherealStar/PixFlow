package com.pixflow.module.vision.image;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.module.vision.analyze.VisionImageRef;
import java.util.Objects;

public record PreparedVisionImage(VisionImageRef ref, ChatMessage.ImagePart part, long sourceBytes, int encodedBytes) {
    public PreparedVisionImage {
        ref = Objects.requireNonNull(ref, "ref");
        part = Objects.requireNonNull(part, "part");
        if (sourceBytes < 0 || encodedBytes < 0) {
            throw new IllegalArgumentException("image sizes must be non-negative");
        }
    }
}
