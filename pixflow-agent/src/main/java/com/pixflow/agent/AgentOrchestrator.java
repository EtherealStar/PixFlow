package com.pixflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.error.AgentErrorCode;
import com.pixflow.agent.memory.MemoryRecallPlanner;
import com.pixflow.agent.memory.MemoryRecallSignal;
import com.pixflow.agent.memory.MemoryRecallTraceSnapshot;
import com.pixflow.agent.planmode.PlanModeController;
import com.pixflow.agent.planmode.PlanModeState;
import com.pixflow.agent.prompt.DynamicPromptAssembler;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.agent.sessionmemory.SessionMemoryService;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.observability.ErrorRecorder;
import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.budget.TokenEstimator;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.compaction.SummarizationPort;
import com.pixflow.harness.context.engine.ContextEngine;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.context.store.TranscriptPort;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.loop.AgentLoop;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.config.LoopAutoConfiguration;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.harness.loop.permission.PermissionContextFactory;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.harness.tools.ToolVisibilityContext;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.module.memory.context.MemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Agent 决策层入口（per-request 装配）。
 *
 * <p>对应 {@code agent.md §十二} 与 {@code loop.md §三} 的 loop/agent 接缝：
 * <ol>
 *   <li>同步召回（{@link MemoryRecallPlanner#plan}）—— 用户决策 3：入口拿当次 prompt + 当次状态</li>
 *   <li>加载 Session Memory（{@link SessionMemoryService#load}）</li>
 *   <li>构造 {@link SectionRenderer.PromptRuntimeContext}（state + visibleTools + recall + sessionMemory）</li>
 *   <li>渲染 {@code systemPrompt} + {@code toolSchemas}</li>
 *   <li>per-call 构造 {@link AgentLoop} + 调 {@code stream(...)} 完成回合</li>
 * </ol>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>本类作为 Spring 单例持有"无状态协作 bean"；{@code AgentLoop} 在每次 {@code streamNewTurn}
 *       现场构造（与 {@code loop.md §十二} 一致——{@code AgentLoop} 不暴露为 Spring bean）</li>
 *   <li>{@link #streamNewTurn(AgentTurnRequest, AgentEventSink)} 是
 *       {@code conversation.AgentTurnRunner} SPI 的生产实现</li>
 *   <li>{@link #continueTurn(String, PermissionPrincipal, AgentEventSink, CancellationToken)}
 *       跳过 user prompt 派发与追加，
 *       复用同一构造路径（fork / 续轮场景）</li>
 *   <li>错误向上抛给 web 层（归一化为 HTTP/SSE error 帧），不 emit error 事件</li>
 * </ul>
 */
@Component
public class AgentOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final MemoryRecallPlanner memoryRecallPlanner;

    private final SessionMemoryService sessionMemoryService;

    private final DynamicPromptAssembler promptAssembler;

    private final ToolRegistry toolRegistry;

    private final PlanModeController planModeController;

    private final AgentProperties agentProperties;

    private final LoopProperties loopProperties;

    private final ObjectMapper jsonMapper;

    private final PermissionPolicy permissionPolicy;

    // 协作 SPI（全部可由 Spring 容器注入；为单例复用）
    private final ChatModelClient chatModelClient;

    private final ToolExecutor toolExecutor;

    private final HookRegistry hookRegistry;

    private final TraceRecorder traceRecorder;

    private final ErrorRecorder errorRecorder;

    private final PermissionContextFactory permissionContextFactory;

    private final TokenEstimator tokenEstimator;

    private final ContextBudgetService contextBudgetService;

    private final ContextCompactionService contextCompactionService;

    private final TranscriptPort transcriptPort;

    private final ExecutorService loopToolExecutor;

    public AgentOrchestrator(MemoryRecallPlanner memoryRecallPlanner,
                             SessionMemoryService sessionMemoryService,
                             DynamicPromptAssembler promptAssembler,
                             ToolRegistry toolRegistry,
                             PlanModeController planModeController,
                             AgentProperties agentProperties,
                             LoopProperties loopProperties,
                             ObjectMapper jsonMapper,
                             PermissionPolicy permissionPolicy,
                             ChatModelClient chatModelClient,
                             ToolExecutor toolExecutor,
                             HookRegistry hookRegistry,
                             TraceRecorder traceRecorder,
                             ErrorRecorder errorRecorder,
                             PermissionContextFactory permissionContextFactory,
                             TokenEstimator tokenEstimator,
                             ContextBudgetService contextBudgetService,
                             ContextCompactionService contextCompactionService,
                             @Qualifier(LoopAutoConfiguration.LOOP_TOOL_EXECUTOR_BEAN) ExecutorService loopToolExecutor,
                             ObjectProvider<TranscriptPort> transcriptPortProvider,
                             ObjectProvider<SummarizationPort> summarizationPortProvider) {
        this.memoryRecallPlanner = Objects.requireNonNull(memoryRecallPlanner, "memoryRecallPlanner");
        this.sessionMemoryService = Objects.requireNonNull(sessionMemoryService, "sessionMemoryService");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.planModeController = Objects.requireNonNull(planModeController, "planModeController");
        this.agentProperties = Objects.requireNonNull(agentProperties, "agentProperties");
        this.loopProperties = loopProperties == null ? new LoopProperties() : loopProperties;
        this.jsonMapper = jsonMapper == null ? new ObjectMapper() : jsonMapper;
        this.permissionPolicy = permissionPolicy;  // may be null if harness-permission module not on classpath
        this.chatModelClient = Objects.requireNonNull(chatModelClient, "chatModelClient");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder");
        this.errorRecorder = Objects.requireNonNull(errorRecorder, "errorRecorder");
        this.permissionContextFactory = permissionContextFactory == null
                ? new DefaultPermissionContextFactory() : permissionContextFactory;
        this.tokenEstimator = tokenEstimator == null
                ? new ConservativeTokenEstimator() : tokenEstimator;
        this.transcriptPort = transcriptPortProvider == null
                ? null : transcriptPortProvider.getIfAvailable();
        // ForkChildSummarizationPort 是 SummarizationPort SPI 的生产实现（agent 模块提供）；
        // 通过 ObjectProvider 解析，避免构造期硬依赖具体实现。
        SummarizationPort summarizationPort = summarizationPortProvider == null
                ? null : summarizationPortProvider.getIfAvailable();
        this.contextBudgetService = contextBudgetService == null
                ? new ContextBudgetService(ContextBudgetConfig.defaults(), this.tokenEstimator, null)
                : contextBudgetService;
        this.contextCompactionService = contextCompactionService == null
                ? new ContextCompactionService(this.contextBudgetService, this.tokenEstimator,
                        summarizationPort, CompactionConfig.defaults())
                : contextCompactionService;
        this.loopToolExecutor = Objects.requireNonNull(loopToolExecutor, "loopToolExecutor");
    }

    /**
     * 同步触发记忆召回。
     *
     * <p>对应 {@code agent.md §十二.2} 步骤 3 — 在 buildForModel 之前
     * 同步执行；本方法抽取出来便于测试。
     */
    public MemoryContext recall(RuntimeState state,
                                String userMessage,
                                List<MessageReference> references,
                                List<String> recentAssistantMessages) {
        Objects.requireNonNull(state, "state");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recent_assistant_messages",
                recentAssistantMessages == null ? List.of() : List.copyOf(recentAssistantMessages));
        metadata.put("runtime_scope", state.runtimeScope() == null ? "" : state.runtimeScope().toString());
        metadata.put("reference_keys", referenceKeys(references));
        MemoryRecallSignal signal = new MemoryRecallSignal(
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                userMessage,
                toMemoryReferences(references),
                List.of(),
                metadata,
                agentProperties.getMemory().getRecall().getMaxTokens()
        );
        MemoryContext context = memoryRecallPlanner.plan(signal);
        state.putMetadata("memoryRecall", MemoryRecallTraceSnapshot.from(context));
        return context;
    }

    /**
     * 构造 {@link SectionRenderer.PromptRuntimeContext}。
     */
    public SectionRenderer.PromptRuntimeContext buildContext(
            RuntimeState state,
            String conversationId,
            Integer turnNo,
            String packageId,
            List<String> attachedSkuIds,
            String userMessage,
            MemoryContext memoryContext,
            SessionMemoryContent sessionMemory,
            List<ToolDescriptor> visibleTools) {
        return new SectionRenderer.PromptRuntimeContext(
                state,
                conversationId,
                turnNo,
                packageId,
                attachedSkuIds,
                List.of(),
                List.of(),
                userMessage,
                planModeController.readPlanMode(state),
                memoryContext,
                sessionMemory,
                visibleTools
        );
    }

    /**
     * 渲染完整 systemPrompt。
     */
    public String renderSystemPrompt(SectionRenderer.PromptRuntimeContext ctx) {
        return promptAssembler.assemble(ctx);
    }

    /**
     * 装配会话内容（入口 helper）：recall + load session memory + 构造 ctx + 渲染。
     */
    public Map<String, Object> prepareTurn(
            RuntimeState state,
            String conversationId,
            Integer turnNo,
            List<MessageReference> references,
            List<String> recentAssistantMessages,
            String userMessage,
            List<ToolDescriptor> visibleTools) {
        // 1. 同步召回
        MemoryContext memoryContext = recall(state, userMessage, references, recentAssistantMessages);
        // 2. 加载 session memory
        SessionMemoryContent sessionMemory = sessionMemoryService
                .load(conversationId).orElse(null);
        // 3. 构造 PromptRuntimeContext
        SectionRenderer.PromptRuntimeContext ctx = buildContext(
                state, conversationId, turnNo, null,
                List.of(), userMessage,
                memoryContext, sessionMemory, visibleTools
        );
        // 4. 渲染 systemPrompt
        String systemPrompt = renderSystemPrompt(ctx);
        LOGGER.info(
                "AgentOrchestrator.prepareTurn: conversationId={}, memoryDegraded={}, "
                        + "memorySections={}, systemPromptLen={}",
                conversationId, memoryContext.degraded(), memoryContext.sections().size(), systemPrompt.length());
        return Map.of(
                "systemPrompt", systemPrompt,
                "memoryContext", memoryContext
        );
    }

    // --------------------------------------------------------------------
    // AgentTurnRunner SPI：完整回合驱动入口（per-request）
    // --------------------------------------------------------------------

    /**
     * 回合入口（用户新消息）：同步驱动整条 think-act-observe 主循环。
     *
     * <p>对应 {@code conversation.AgentTurnRunner} 的生产实现：
     * <pre>
     *   1. 构造 RuntimeState（per-conversation） + MessageStore（ephemeral + transcriptPort）
     *   2. rehydrate（本期由调用方在 conversation 侧预填；这里从空链起步）
     *   3. 同步召回 + 加载 session memory
     *   4. 渲染 systemPrompt + 投影 toolSchemas
     *   5. 构造 AgentLoop（per-call 装配）
     *   6. 调 AgentLoop.stream(prompt, systemPrompt, toolSchemas, sink) 阻塞跑完
     *   7. 回合结束：messageStore.flush()；sink.emit(COMPLETED) 由 AgentLoop 内部发出
     * </pre>
     *
     * <p>AgentTurnRunner SPI 返回值约定：返回 AgentLoop 的最终 assistant 文本
     * （final text，与 SSE `event: completed` 帧的 finalText 一致）。
     *
     * @param conversationId 会话 id
     * @param prompt         用户消息文本（可空字符串表示续轮但不派发 USER_PROMPT_SUBMIT）
     * @param request        包含 Conversation 已校验的有序消息引用
     * @param sink           SSE 事件接出
     * @return AgentLoop 返回的最终 assistant 文本
     */
    public String streamNewTurn(AgentTurnRequest request, AgentEventSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        request.cancellation().throwIfCancellationRequested();

        TurnRuntime runtime = createTurnRuntime(
                request.conversationId(), request.principal(), TurnMode.NEW_TURN);
        return driveTurn(runtime.state(), runtime.messageStore(), request.conversationId(),
                runtime.targetTurnNo(), request.prompt(), request.references(), sink,
                TurnMode.NEW_TURN, request.cancellation());
    }

    /**
     * 续轮入口（fork child / 恢复场景）：消息链已 seed，不追加 user prompt。
     *
     * <p>对应 {@code loop.md §十二} 的 continueStream 接缝；与 {@link #streamNewTurn}
     * 共享同一 driveTurn 实现，仅跳过 hook 派发与 appendUser。
     *
     * @param conversationId 会话 id
     * @param sink           SSE 事件接出
     * @return AgentLoop 返回的最终 assistant 文本
     */
    public String continueTurn(
            String conversationId,
            PermissionPrincipal principal,
            AgentEventSink sink,
            CancellationToken cancellation) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancellationRequested();

        // 续轮也必须由认证边界显式传入主体，不能从历史消息或 metadata 恢复身份。
        TurnRuntime runtime = createTurnRuntime(conversationId, principal, TurnMode.CONTINUE);
        return driveTurn(runtime.state(), runtime.messageStore(), conversationId,
                runtime.targetTurnNo(), "", List.of(), sink, TurnMode.CONTINUE, cancellation);
    }

    /**
     * 主驱动路径：8 步编排（recall → render → drive AgentLoop.stream）。
     */
    private String driveTurn(RuntimeState state,
                             MessageStore messageStore,
                             String conversationId,
                             int targetTurnNo,
                             String prompt,
                             List<MessageReference> references,
                             AgentEventSink sink,
                             TurnMode mode,
                             CancellationToken cancellation) {
        Objects.requireNonNull(sink, "sink");
        try {
            cancellation.throwIfCancellationRequested();
            state.setTurnNo(targetTurnNo);

            // 1. 同步召回。USER_PROMPT_SUBMIT 与 user message append 由 AgentLoop.stream 统一负责。
            List<String> recentAssistant = recentAssistantTexts(messageStore);
            MemoryContext memoryContext;
            try {
                memoryContext = recall(state, prompt, references, recentAssistant);
            } catch (RuntimeException recallFailure) {
                // Memory 门面异常时仍允许对话继续，避免只读召回成为可用性单点。
                memoryContext = new MemoryContext(conversationId, targetTurnNo, List.of(),
                        Map.of("degraded_reason", "agent_memory_recall_failed"), true);
                state.putMetadata("memoryRecall", MemoryRecallTraceSnapshot.failed(
                        "agent_memory_recall_failed",
                        agentProperties.getMemory().getRecall().getMaxTokens()));
            }
            // 2. 加载 session memory
            SessionMemoryContent sessionMemory = sessionMemoryService
                    .load(conversationId).orElse(null);

            // 3. 渲染 systemPrompt
            List<ToolDescriptor> visibleTools = currentVisibleTools(state);
            SectionRenderer.PromptRuntimeContext ctx = buildContext(
                    state, conversationId, targetTurnNo, null,
                    List.of(), prompt == null ? "" : prompt,
                    memoryContext, sessionMemory, visibleTools);
            String systemPrompt = renderSystemPrompt(ctx);
            // 4. 投影 toolSchemas
            List<ToolSchemaView> toolSchemas = toToolSchemaViews(visibleTools);

            // AgentLoop 入口会自增 turnNo；这里回退一格，确保 loop 记录目标回合号。
            state.setTurnNo(Math.max(0, targetTurnNo - 1));

            // 5. per-call 构造 AgentLoop
            AgentLoop loop = buildAgentLoop(state, messageStore);

            // 6. 阻塞跑完。continueTurn 不追加新的 USER message。
            String finalText = mode == TurnMode.CONTINUE
                    ? loop.continueStream(sink, systemPrompt, toolSchemas, cancellation)
                    : loop.stream(prompt == null ? "" : prompt,
                            references == null ? List.of() : references,
                            sink, systemPrompt, toolSchemas, cancellation);

            // 8. 收尾：flush 由 AgentLoop 内部触发；返回 finalText 给调用方（作为 turn result）
            LOGGER.info("AgentOrchestrator: conversationId={} turnNo={} finalTextLen={}",
                    conversationId, state.turnNo(),
                    finalText == null ? 0 : finalText.length());
            return finalText == null ? "" : finalText;
        } catch (OperationCancelledException cancelled) {
            throw cancelled;
        } catch (PixFlowException pfe) {
            // AgentLoop 已记录 abort；这里再通过 ErrorRecorder 落盘后向上抛
            try {
                errorRecorder.record(pfe);
            } catch (RuntimeException ignored) {
                // recorder 二次异常不影响主异常上抛
            }
            throw pfe;
        } catch (RuntimeException ex) {
            PixFlowException error = toPixFlow(ex);
            try {
                errorRecorder.record(error);
            } catch (RuntimeException ignored) {
            }
            throw error;
        } finally {
            try {
                messageStore.flush();
            } catch (RuntimeException flushError) {
                LOGGER.warn(
                        "AgentOrchestrator: message flush failed for conversationId={}",
                        conversationId,
                        flushError);
            }
        }
    }

    /**
     * 构造 per-conversation 的 {@link RuntimeState}：traceId 用 UUID，turnNo 从 1 起。
     */
    private RuntimeState newState(
            String conversationId,
            com.pixflow.harness.permission.PermissionPrincipal principal,
            int targetTurnNo) {
        RuntimeState state = new RuntimeState();
        state.setConversationId(conversationId);
        state.setTraceId(UUID.randomUUID().toString());
        state.setTurnNo(targetTurnNo);
        state.setRuntimeScope(RuntimeScope.main());
        state.setPermissionPrincipal(principal);
        state.setPermissionPlanMode(PermissionPlanMode.OFF);
        return state;
    }

    private TurnRuntime createTurnRuntime(
            String conversationId,
            com.pixflow.harness.permission.PermissionPrincipal principal,
            TurnMode mode) {
        MessageStore messageStore = MessageStore.transcriptBacked(transcriptPort);
        messageStore.bindConversation(conversationId);
        List<com.pixflow.harness.context.model.Message> seed = loadTranscript(conversationId);
        messageStore.seedMessages(seed);
        int userTurnCount = countUserTurns(seed);
        int targetTurnNo = mode == TurnMode.CONTINUE
                ? Math.max(1, userTurnCount)
                : userTurnCount + 1;
        RuntimeState state = newState(conversationId, principal, targetTurnNo);
        return new TurnRuntime(state, messageStore, targetTurnNo);
    }

    private List<com.pixflow.harness.context.model.Message> loadTranscript(String conversationId) {
        if (transcriptPort == null) {
            return List.of();
        }
        List<com.pixflow.harness.context.model.Message> loaded = transcriptPort.load(conversationId);
        return loaded == null ? List.of() : loaded;
    }

    private static int countUserTurns(List<com.pixflow.harness.context.model.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (com.pixflow.harness.context.model.Message message : messages) {
            if (message.role() == com.pixflow.harness.context.model.MessageRole.USER
                    && !message.metadata().values().containsKey(
                            com.pixflow.harness.context.model.MessageMetadata.EVENT)) {
                count++;
            }
        }
        return count;
    }

    private enum TurnMode {
        NEW_TURN,
        CONTINUE
    }

    private record TurnRuntime(RuntimeState state, MessageStore messageStore, int targetTurnNo) {
    }

    /**
     * per-call 装配 {@link AgentLoop}：15 依赖全部就绪。
     *
     * <p>与 {@code LoopTestSupport.Builder.build()} 同款形状——本方法是生产路径，
     * 全部 bean 都来自 Spring 容器（无 fake）。
     *
     * <p>{@code PlanModeView} 不能是单例 bean（{@code RuntimeState} per-conversation 实例），
     * 本方法 per-call 构造一个 lambda 视图，绑定当前 {@code state}。
     * {@code ToolResultStorage} 本期未装配（外部资源外置由上层完成），传 null。
     */
    private AgentLoop buildAgentLoop(RuntimeState state, MessageStore messageStore) {
        ContextEngine contextEngine = new ContextEngine(
                messageStore, contextCompactionService, new CurrentModelContext());
        PlanModeView planModeView = () -> planModeController.readPlanMode(state) == PlanModeState.ACTIVE;
        return new AgentLoop(
                state,
                messageStore,
                contextEngine,
                contextCompactionService,
                chatModelClient,
                toolExecutor,
                permissionPolicy,
                null,                       // resultStorage：外部资源外置由上层完成，本期未启用
                planModeView,
                hookRegistry,
                traceRecorder,
                permissionContextFactory,
                errorRecorder,
                loopProperties,
                jsonMapper,
                loopToolExecutor);
    }

    /**
     * 计算当前可见工具集：透传 {@link ToolRegistry#visibleDescriptors}。
     *
     * <p>Plan 模式裁剪由 ToolRegistry 内部完成（AgentLoop 通过 {@code hiddenTools} 注入）；
     * 本方法只读取 RuntimeState.metadata["hiddenTools"] 作为补充。
     */
    @SuppressWarnings("unchecked")
    private List<ToolDescriptor> currentVisibleTools(RuntimeState state) {
        com.pixflow.harness.permission.PermissionContext permissionCtx =
                permissionContextFactory.create(state);
        Set<String> hidden = hiddenTools(state);
        ToolVisibilityContext visibility = new ToolVisibilityContext(
                permissionCtx,
                planModeController.readPlanMode(state) == PlanModeState.ACTIVE,
                hidden);
        List<ToolDescriptor> descriptors = toolRegistry.visibleDescriptors(visibility);
        return descriptors == null ? List.of() : descriptors;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> hiddenTools(RuntimeState state) {
        Object value = state.metadataOrDefault("hiddenTools", Set.of());
        if (value instanceof Set<?> s) {
            return s.stream().filter(Objects::nonNull).map(String::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof java.util.Collection<?> c) {
            return c.stream().filter(Objects::nonNull).map(String::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        return Set.of();
    }

    private static List<ToolSchemaView> toToolSchemaViews(List<ToolDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        List<ToolSchemaView> result = new ArrayList<>(descriptors.size());
        for (ToolDescriptor d : descriptors) {
            Map<String, Object> schema = d.inputSchema() == null ? Map.of() : d.inputSchema();
            result.add(new ToolSchemaView(
                    d.name(),
                    d.description() == null ? "" : d.description(),
                    schema));
        }
        return result;
    }

    /**
     * 取最近 N 条 assistant message 文本（本期固定 N=3，与 MemoryRecallPlanner 默认对齐）。
     */
    private static List<String> recentAssistantTexts(MessageStore messageStore) {
        List<String> all = messageStore.currentMessages().stream()
                .filter(m -> m.role() == com.pixflow.harness.context.model.MessageRole.ASSISTANT)
                .map(m -> m.content() == null ? "" : m.content())
                .toList();
        if (all.size() <= 3) {
            return all;
        }
        return all.subList(all.size() - 3, all.size());
    }

    private static List<String> referenceKeys(List<MessageReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        return references.stream().map(MessageReference::referenceKey).toList();
    }

    private static List<com.pixflow.module.memory.context.MemoryReference> toMemoryReferences(
            List<MessageReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        return references.stream().map(reference -> new com.pixflow.module.memory.context.MemoryReference(
                reference.referenceKey(), reference.displayPathSnapshot())).toList();
    }

    private static PixFlowException toPixFlow(Throwable error) {
        if (error instanceof PixFlowException pf) {
            return pf;
        }
        return new PixFlowException(
                AgentErrorCode.AGENT_PROMPT_ASSEMBLY_FAILED,
                error == null ? "unknown error" : error.getMessage(),
                error);
    }
}
