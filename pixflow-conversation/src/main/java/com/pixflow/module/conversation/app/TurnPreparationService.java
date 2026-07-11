package com.pixflow.module.conversation.app;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.attachment.Attachment;
import com.pixflow.module.conversation.attachment.AttachmentCollector;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.attachment.PackageBinding;
import com.pixflow.module.conversation.attachment.UserPrompt;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;

public final class TurnPreparationService {
    private final ConversationService conversationService;
    private final ConversationLock conversationLock;
    private final AttachmentCollector attachmentCollector;
    private final AttachmentMapper attachmentMapper;
    private final AgentTurnRunnerRegistry agentTurnRunnerRegistry;

    public TurnPreparationService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            AttachmentCollector attachmentCollector,
            AttachmentMapper attachmentMapper,
            AgentTurnRunnerRegistry agentTurnRunnerRegistry) {
        this.conversationService = conversationService;
        this.conversationLock = conversationLock;
        this.attachmentCollector = attachmentCollector;
        this.attachmentMapper = attachmentMapper;
        this.agentTurnRunnerRegistry = agentTurnRunnerRegistry;
    }

    public PreparedTurn prepare(long ownerUserId, String conversationId, MessageSubmitRequest request) {
        // owner 校验必须在附件读取和加锁前完成，避免越权请求触碰关联资源。
        conversationService.requireActive(ownerUserId, conversationId);
        UserPrompt prompt = new UserPrompt(
                request == null ? "" : request.prompt(),
                request == null ? List.of() : request.attachments());
        PackageBinding binding = new PackageBinding(request == null ? null : request.packageId());
        TurnLockHandle lockHandle = conversationLock.tryLock(conversationId)
                .orElseThrow(() -> new PixFlowException(
                        ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                        "conversation is busy: " + conversationId));
        try {
            List<Attachment> attachments = collectAttachments(prompt, binding);
            AgentTurnRunner runner = agentTurnRunnerRegistry.resolve();
            return new PreparedTurn(
                    ownerUserId,
                    conversationId,
                    prompt.text(),
                    attachmentMapper.toLoopAttachments(attachments),
                    runner,
                    lockHandle);
        } catch (RuntimeException error) {
            lockHandle.close();
            throw error;
        }
    }

    private List<Attachment> collectAttachments(UserPrompt prompt, PackageBinding binding) {
        boolean direct = prompt.attachments() != null && !prompt.attachments().isEmpty();
        boolean packaged = binding.present();
        if (!direct && !packaged) {
            return List.of();
        }
        if (attachmentCollector == null) {
            throw new PixFlowException(
                    ConversationErrorCode.ATTACHMENT_INVALID,
                    "attachment support is not configured");
        }
        return attachmentCollector.collect(prompt, binding);
    }
}
