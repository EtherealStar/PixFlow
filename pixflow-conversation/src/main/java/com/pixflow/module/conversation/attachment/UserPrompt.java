package com.pixflow.module.conversation.attachment;

import java.util.List;

public record UserPrompt(String text, List<UserAttachmentInput> attachments) {
    public UserPrompt {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
