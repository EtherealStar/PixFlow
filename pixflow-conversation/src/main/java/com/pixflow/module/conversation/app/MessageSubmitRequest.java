package com.pixflow.module.conversation.app;

import com.pixflow.module.conversation.attachment.UserAttachmentInput;
import java.util.List;
import java.util.Map;

public record MessageSubmitRequest(
        String prompt,
        List<UserAttachmentInput> attachments,
        String packageId,
        Map<String, Object> metadata) {
}
