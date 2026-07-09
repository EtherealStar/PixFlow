package com.pixflow.module.conversation.attachment;

import java.util.Map;
import java.util.Objects;

public record Attachment(
        String attachmentId,
        AttachmentType type,
        String sourceRef,
        String packageId,
        Map<String, Object> metadata) {

    public Attachment {
        attachmentId = requireText(attachmentId, "attachmentId");
        type = Objects.requireNonNull(type, "type");
        sourceRef = requireText(sourceRef, "sourceRef");
        packageId = packageId == null || packageId.isBlank() ? null : packageId.trim();
        metadata = AttachmentMetadata.normalize(metadata);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
