package com.pixflow.module.conversation.app;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.AgentTurnRunner;
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
import java.util.function.Supplier;

/**
 * 回合编排入口。
 *
 * <p>把"加锁 → 收集附件 → 调 agent runner"串起来,但**故意不释放锁**。
 * 锁的释放由调用方在 SSE emitter 已 complete 之后做(见 {@link com.pixflow.module.conversation.api.MessageController}),
 * 保证锁释放和连接关闭之间没有 race window——同一个会话不会
 * 在旧 emitter 还残留时被并发启动新一轮回合。
 *
 * <p>本类持有 {@link AgentTurnRunnerRegistry}({@code AgentTurnRunner} SPI 的运行时选择器),
 * 在每次 {@link #dispatch} 时再 resolve 当前的 runner——这样 production 环境(agent 模块在)
 * 自动选中 {@code AgentOrchestrator}; 单模块 / 测试场景(无 agent)回退到 unavailable 占位。
 */
public class TurnDispatchService {
    private final ConversationService conversationService;
    private final ConversationLock conversationLock;
    private final AttachmentCollector attachmentCollector;
    private final AttachmentMapper attachmentMapper;
    private final AgentTurnRunnerRegistry agentTurnRunnerRegistry;

    public TurnDispatchService(
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

    /**
     * 取锁后调 {@link AgentTurnRunner#stream}。
     *
     * <p>返回的 {@link TurnLockHandle} 由调用方持有,在 SSE emitter 已关闭后再 {@link TurnLockHandle#close()}。
     * 调用方拿到 handle 后应:
     * <pre>
     *   try (handle) {
     *       runnerFunction.apply(handle);   // 内部会用 handle 串起 stream + emitter 关闭
     *   }
     * </pre>
     */
    public DispatchHandle dispatch(long ownerUserId, String conversationId, MessageSubmitRequest request, AgentEventSink sink) {
        conversationService.requireActive(ownerUserId, conversationId);
        UserPrompt prompt = new UserPrompt(request == null ? "" : request.prompt(),
                request == null ? List.of() : request.attachments());
        PackageBinding binding = new PackageBinding(request == null ? null : request.packageId());
        TurnLockHandle handle = conversationLock.tryLock(conversationId)
                .orElseThrow(() -> new PixFlowException(ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                        "conversation is busy: " + conversationId));
        try {
            List<Attachment> attachments = collectAttachments(prompt, binding);
            // 每回合 resolve 一次 runner, 让 AgentOrchestrator bean 出现时自动接管
            AgentTurnRunner runner = agentTurnRunnerRegistry.resolve();
            Supplier<String> runnerFunction = () -> runner.stream(
                    conversationId,
                    prompt.text(),
                    attachmentMapper.toLoopAttachments(attachments),
                    sink);
            return new DispatchHandle(handle, runnerFunction);
        } catch (RuntimeException ex) {
            // 锁后初始化失败必须主动释放，避免 controller 尚未拿到 handle 时泄漏 turn lock。
            handle.close();
            throw ex;
        }
    }

    private List<Attachment> collectAttachments(UserPrompt prompt, PackageBinding binding) {
        boolean hasDirectAttachments = prompt != null && prompt.attachments() != null && !prompt.attachments().isEmpty();
        boolean hasPackageBinding = binding != null && binding.present();
        if (!hasDirectAttachments && !hasPackageBinding) {
            return List.of();
        }
        if (attachmentCollector == null) {
            throw new PixFlowException(ConversationErrorCode.ATTACHMENT_INVALID,
                    "attachment support is not configured");
        }
        return attachmentCollector.collect(prompt, binding);
    }

    /**
     * 编排句柄:让调用方在 SSE 已关闭后才释放 turn 锁,避免旧连接与新回合重叠。
     */
    public static final class DispatchHandle implements AutoCloseable {
        private final TurnLockHandle lockHandle;
        private final Supplier<String> runner;

        DispatchHandle(TurnLockHandle lockHandle, Supplier<String> runner) {
            this.lockHandle = lockHandle;
            this.runner = runner;
        }

        public String run() {
            return runner.get();
        }

        public TurnLockHandle lockHandle() {
            return lockHandle;
        }

        @Override
        public void close() {
            lockHandle.close();
        }
    }
}
