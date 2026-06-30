package com.pixflow.module.conversation.attachment;

import java.util.Map;

public record UserAttachmentInput(
        String attachmentId,
        AttachmentType type,
        String sourceRef,
        String packageId,
        Map<String, Object> metadata) {
}
