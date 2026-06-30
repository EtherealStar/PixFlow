package com.pixflow.module.conversation.app;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.module.conversation.attachment.Attachment;
import com.pixflow.module.conversation.attachment.AttachmentCollector;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.attachment.PackageBinding;
import com.pixflow.module.conversation.attachment.UserPrompt;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;

public class TurnDispatchService {
    private final ConversationService conversationService;
    private final ConversationLock conversationLock;
    private final AttachmentCollector attachmentCollector;
    private final AttachmentMapper attachmentMapper;
    private final AgentTurnRunner agentTurnRunner;

    public TurnDispatchService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            AttachmentCollector attachmentCollector,
            AttachmentMapper attachmentMapper,
            AgentTurnRunner agentTurnRunner) {
        this.conversationService = conversationService;
        this.conversationLock = conversationLock;
        this.attachmentCollector = attachmentCollector;
        this.attachmentMapper = attachmentMapper;
        this.agentTurnRunner = agentTurnRunner;
    }

    public String stream(String conversationId, MessageSubmitRequest request, AgentEventSink sink) {
        conversationService.requireActive(conversationId);
        UserPrompt prompt = new UserPrompt(request == null ? "" : request.prompt(),
                request == null ? List.of() : request.attachments());
        List<Attachment> attachments = attachmentCollector.collect(
                prompt,
                new PackageBinding(request == null ? null : request.packageId()));

        TurnLockHandle handle = conversationLock.tryLock(conversationId)
                .orElseThrow(() -> new PixFlowException(ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                        "conversation is busy: " + conversationId));
        try (handle) {
            return agentTurnRunner.stream(
                    conversationId,
                    prompt.text(),
                    attachmentMapper.toLoopAttachments(attachments),
                    sink);
        }
    }
}
