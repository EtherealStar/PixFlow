package com.pixflow.module.conversation.app;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;

public final class TurnPreparationService {
    private final ConversationService conversationService;

    private final ConversationLock conversationLock;

    private final MessageReferenceValidator referenceValidator;

    private final AgentTurnRunnerRegistry agentTurnRunnerRegistry;

    public TurnPreparationService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            MessageReferenceValidator referenceValidator,
            AgentTurnRunnerRegistry agentTurnRunnerRegistry) {
        this.conversationService = conversationService;
        this.conversationLock = conversationLock;
        this.referenceValidator = referenceValidator;
        this.agentTurnRunnerRegistry = agentTurnRunnerRegistry;
    }

    public PreparedTurn prepare(
            AuthPrincipal principal, String conversationId, MessageSubmitRequest request) {
        long ownerUserId = principal.userId();
        // owner 校验必须在素材事实读取和加锁前完成，避免越权请求触碰关联资源。
        conversationService.requireActive(ownerUserId, conversationId);
        String prompt = request == null || request.prompt() == null ? "" : request.prompt();
        PermissionPrincipal permissionPrincipal = new PermissionPrincipal(
                Long.toString(ownerUserId), principal.username());
        List<MessageReference> references = referenceValidator.validate(
                permissionPrincipal,
                conversationId,
                request == null ? List.of() : request.references());
        if (prompt.isBlank() && references.isEmpty()) {
            throw new PixFlowException(
                    ConversationErrorCode.MESSAGE_REFERENCE_INVALID,
                    "prompt or references is required");
        }
        // 引用校验不占用 turn lock；只有完整准入的请求才参与会话串行化。
        TurnLockHandle lockHandle = conversationLock.tryLock(conversationId)
                .orElseThrow(() -> new PixFlowException(
                        ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                        "conversation is busy: " + conversationId));
        try {
            AgentTurnRunner runner = agentTurnRunnerRegistry.resolve();
            return new PreparedTurn(
                    ownerUserId,
                    principal.username(),
                    conversationId,
                    prompt,
                    references,
                    runner,
                    lockHandle);
        } catch (RuntimeException error) {
            lockHandle.close();
            throw error;
        }
    }
}
