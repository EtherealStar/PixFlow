package com.pixflow.module.conversation.attachment;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import java.util.List;

public class AttachmentMapper {
    public List<Message> toContextMessages(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream().map(this::toContextMessage).toList();
    }

    public Message toContextMessage(Attachment attachment) {
        MessageMetadata metadata = MessageMetadata.empty()
                .with(MessageMetadata.ATTACHMENT_ID, attachment.attachmentId())
                .with(MessageMetadata.ATTACHMENT_TYPE, attachment.type().name())
                .with(MessageMetadata.ATTACHMENT_REF, attachment.sourceRef())
                .with(MessageMetadata.ATTACHED_PACKAGE_ID, attachment.packageId())
                .with("attachmentMetadata", attachment.metadata());
        return Message.attachment(attachment.sourceRef()).withMetadata(metadata);
    }

    public List<com.pixflow.harness.loop.Attachment> toLoopAttachments(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new com.pixflow.harness.loop.Attachment(
                        attachment.attachmentId(),
                        attachment.type() == AttachmentType.PACKAGE_REFERENCE ? "package_image" : "upload_image",
                        attachment.sourceRef(),
                        attachment.metadata()))
                .toList();
    }
}
