package com.pixflow.harness.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.observability.ErrorRecorder;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.engine.ContextEngine;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.snapshot.ContextSnapshot;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.AssistantMessagePayload;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.hooks.payload.TurnStoppedPayload;
import com.pixflow.harness.hooks.payload.UserPromptSubmitPayload;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.harness.loop.permission.PermissionContextFactory;
import com.pixflow.harness.loop.recovery.GateDecision;
import com.pixflow.harness.loop.recovery.OutputInterruptHandler;
import com.pixflow.harness.loop.recovery.ReactiveCompactionGate;
import com.pixflow.harness.loop.stream.ModelStreamConsumer;
import com.pixflow.harness.loop.stream.ModelStreamConsumer.ModelOutcome;
import com.pixflow.harness.loop.trace.LoopToolTraceSink;
import com.pixflow.harness.loop.trace.RuntimeScopeTranslator;
import com.pixflow.harness.loop.trace.TraceFanout;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.tools.ToolCall;
import com.pixflow.harness.tools.ToolExecutionContext;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.ToolResultStorage;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolSchema;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Agent 决策回合的驱动核心：手写显式 while 循环，编排 think-act-observe。
 *
 * <p>执行模型：阻塞主循环 + {@link AgentEventSink} 同步 emit；与 infra/ai 的 Reactor 接缝点是
 * {@link ModelStreamConsumer#consume} 内部 {@code publishOn(boundedElastic) + blockLast}，把流式
 * 事件桥接到回合线程同步消费，保证「sink.emit 与 onCompleted 在同一线程串行执行」。
 *
 * <p>续轮只看 {@link ModelOutcome#hasToolCalls}；{@code StopReason.LENGTH} 触发
 * {@link OutputInterruptHandler}；{@code CONTEXT_LIMIT} 触发 {@link ReactiveCompactionGate}；
 * 不可恢复异常 → {@code TurnTrace.abort} + {@code ErrorRecorder.record} + 向上抛，不 emit error 事件。
 *
 * <p>loop 自身不组装 prompt、不决定可见工具集、不评估权限；这些由调用方（agent 层）传入
 * {@link #stream} 的 systemPrompt / toolSchemas。
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final RuntimeState state;
    private final MessageStore messageStore;
    private final ContextEngine contextEngine;
    private final ContextCompactionService compactionService;
    private final ChatModelClient modelClient;
    private final ModelRetryRunner retryRunner;
    private final ToolExecutor toolExecutor;
    private final PermissionPolicy permissionPolicy;
    private final ToolResultStorage resultStorage;
    private final PlanModeView planModeView;
    private final HookRegistry hookRegistry;
    private final TraceRecorder traceRecorder;
    private final PermissionContextFactory permissionContextFactory;
    private final ErrorRecorder errorRecorder;
    private final ModelStreamConsumer streamConsumer;
    private final ReactiveCompactionGate reactiveCompactionGate;
    private final OutputInterruptHandler outputInterruptHandler;
    private final LoopProperties properties;
    private final ObjectMapper jsonMapper;
    private final ExecutorService toolExecutorService;
    private final String defaultContinuationPrompt;

    public AgentLoop(RuntimeState state,
                    MessageStore messageStore,
                    ContextEngine contextEngine,
                    ContextCompactionService compactionService,
                    ChatModelClient modelClient,
                    ModelRetryRunner retryRunner,
                    ToolExecutor toolExecutor,
                    PermissionPolicy permissionPolicy,
                    ToolResultStorage resultStorage,
                    PlanModeView planModeView,
                    HookRegistry hookRegistry,
                    TraceRecorder traceRecorder,
                    PermissionContextFactory permissionContextFactory,
                    ErrorRecorder errorRecorder,
                    LoopProperties properties) {
        this(state, messageStore, contextEngine, compactionService, modelClient, retryRunner,
                toolExecutor, permissionPolicy, resultStorage, planModeView,
                hookRegistry, traceRecorder, permissionContextFactory, errorRecorder, properties,
                new ObjectMapper());
    }

    public AgentLoop(RuntimeState state,
                    MessageStore messageStore,
                    ContextEngine contextEngine,
                    ContextCompactionService compactionService,
                    ChatModelClient modelClient,
                    ModelRetryRunner retryRunner,
                    ToolExecutor toolExecutor,
                    PermissionPolicy permissionPolicy,
                    ToolResultStorage resultStorage,
                    PlanModeView planModeView,
                    HookRegistry hookRegistry,
                    TraceRecorder traceRecorder,
                    PermissionContextFactory permissionContextFactory,
                    ErrorRecorder errorRecorder,
                    LoopProperties properties,
                    ObjectMapper jsonMapper) {
        this.state = Objects.requireNonNull(state, "state");
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
        this.contextEngine = Objects.requireNonNull(contextEngine, "contextEngine");
        this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.retryRunner = Objects.requireNonNull(retryRunner, "retryRunner");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.permissionPolicy = permissionPolicy;
        this.resultStorage = resultStorage;
        this.planModeView = planModeView;
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder");
        this.permissionContextFactory = Objects.requireNonNull(permissionContextFactory, "permissionContextFactory");
        this.errorRecorder = Objects.requireNonNull(errorRecorder, "errorRecorder");
        this.properties = properties == null ? new LoopProperties() : properties;
        this.jsonMapper = jsonMapper == null ? new ObjectMapper() : jsonMapper;
        this.streamConsumer = new ModelStreamConsumer();
        this.reactiveCompactionGate = new ReactiveCompactionGate(compactionService, this.properties);
        this.outputInterruptHandler = new OutputInterruptHandler(this.properties);
        int poolSize = this.properties.toolConcurrencyPoolSize();
        this.toolExecutorService = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "loop-tool-" + poolSize);
            t.setDaemon(true);
            return t;
        });
        this.defaultContinuationPrompt = "[continue where you left off; the previous response was truncated by output limit]";
    }

    public String stream(String prompt,
                         List<Attachment> attachments,
                         AgentEventSink sink,
                         String systemPrompt,
                         List<ToolSchemaView> toolSchemas) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(sink, "sink");
        state.setTraceId(UUID.randomUUID().toString());
        state.setTurnNo(state.turnNo() + 1);

        UserPromptSubmitPayload upsPayload = new UserPromptSubmitPayload(
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                state.runtimeScope(),
                prompt,
                attachmentsToMap(attachments),
                state.metadata());
        hookRegistry.dispatch(HookEvent.USER_PROMPT_SUBMIT, upsPayload);

        messageStore.appendUser(prompt);
        if (attachments != null && !attachments.isEmpty()) {
            List<Message> attachmentMessages = attachments.stream()
                    .map(att -> Message.attachment(att.reference()))
                    .collect(Collectors.toList());
            messageStore.appendAttachments(attachmentMessages);
        }
        return runLoop(sink, systemPrompt, toolSchemas);
    }

    public String continueStream(AgentEventSink sink,
                                 String systemPrompt,
                                 List<ToolSchemaView> toolSchemas) {
        Objects.requireNonNull(sink, "sink");
        if (state.traceId() == null || state.traceId().isBlank()) {
            state.setTraceId(UUID.randomUUID().toString());
        }
        state.setTurnNo(state.turnNo() + 1);
        return runLoop(sink, systemPrompt, toolSchemas);
    }

    private String runLoop(AgentEventSink sink,
                           String systemPrompt,
                           List<ToolSchemaView> toolSchemas) {
        if (state.conversationId() == null || state.conversationId().isBlank()) {
            throw new IllegalStateException("RuntimeState.conversationId must be set before running loop");
        }
        try (TurnTrace turnTrace = traceRecorder.begin(
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                RuntimeScopeTranslator.toEval(state.runtimeScope()))) {
            TraceFanout traceFanout = new TraceFanout(turnTrace);
            String finalText = "";
            try {
                while (true) {
                    state.incrementIteration();

                    ContextEngine.BuildResult build = contextEngine.buildForModel(systemPrompt, toolSchemas);
                    traceFanout.fanoutPrune(build.pruneEntries());
                    ContextSnapshot snapshot = build.snapshot();

                    turnTrace.recordInput(new com.pixflow.harness.eval.model.TraceInput(
                            Instant.now(),
                            "model." + state.iterationCount(),
                            previewText(snapshot.systemPrompt()),
                            "messages#" + snapshot.messages().size(),
                            snapshot.toolSchemas().stream().map(ToolSchemaView::name).toList(),
                            Map.of(
                                    "iteration", state.iterationCount(),
                                    "lastTransition", state.lastTransition() == null ? "" : state.lastTransition().name())));

                    ChatRequest request = toChatRequest(snapshot);
                    Flux<com.pixflow.infra.ai.chat.ChatStreamEvent> flux = retryRunner.run(
                            ModelRole.PRIMARY_CHAT,
                            attempt -> modelClient.stream(request));
                    ModelOutcome outcome;
                    try {
                        outcome = streamConsumer.consume(flux, sink, state);
                    } catch (RuntimeException ex) {
                        if (isContextLimit(ex)) {
                            PixFlowException ctxErr = toPixFlow(ex);
                            GateDecision gate = reactiveCompactionGate.onContextLimit(state, messageStore, ctxErr);
                            applyTransition(state, gate, sink, traceFanout);
                            continue;
                        }
                        throw ex;
                    }
                    state.addUsage(outcome.usage());

                    if (outcome.outputInterrupted()) {
                        GateDecision gate = outputInterruptHandler.onOutputInterrupted(
                                state, messageStore, outcome.finalText(), defaultContinuationPrompt);
                        applyTransition(state, gate, sink, traceFanout);
                        continue;
                    }

                    finalText = outcome.finalText() == null ? "" : outcome.finalText();
                    Message assistantMsg = Message.assistant(finalText);
                    if (!outcome.toolCalls().isEmpty()) {
                        MessageMetadata md = MessageMetadata.empty()
                                .with(MessageMetadata.TOOL_CALL_IDS,
                                        outcome.toolCalls().stream()
                                                .map(com.pixflow.infra.ai.chat.ToolCall::id)
                                                .filter(Objects::nonNull)
                                                .toList());
                        assistantMsg = assistantMsg.withMetadata(md);
                    }
                    messageStore.appendAssistant(assistantMsg);

                    AssistantMessagePayload ampPayload = new AssistantMessagePayload(
                            state.conversationId(),
                            state.turnNo(),
                            state.traceId(),
                            state.runtimeScope(),
                            assistantMsg.id(),
                            finalText,
                            outcome.toolCalls().stream()
                                    .map(com.pixflow.infra.ai.chat.ToolCall::id)
                                    .filter(Objects::nonNull)
                                    .toList(),
                            state.metadata());
                    HookResult ampResult = hookRegistry.dispatch(HookEvent.ASSISTANT_MESSAGE_COMPLETED, ampPayload);
                    traceFanout.fanoutHookSpan(HookEvent.ASSISTANT_MESSAGE_COMPLETED, ampResult, null, null, 0L);

                    sink.emit(AgentEvent.assistantCompleted(finalText, assistantMsg.id(), state.metadata()));

                    if (!outcome.hasToolCalls()) {
                        state.setTransition(TransitionReason.COMPLETED);
                        sink.emit(AgentEvent.transition(TransitionReason.COMPLETED,
                                Map.of("iteration", state.iterationCount())));

                        HookResult stopped = hookRegistry.dispatch(HookEvent.TURN_STOPPED,
                                new TurnStoppedPayload(
                                        state.conversationId(),
                                        state.turnNo(),
                                        state.traceId(),
                                        state.runtimeScope(),
                                        "completed",
                                        state.metadata()));
                        traceFanout.fanoutHookSpan(HookEvent.TURN_STOPPED, stopped, null, null, 0L);

                        try {
                            messageStore.flush();
                        } catch (RuntimeException ignored) {
                            // flush 当前 no-op；保留入口以对齐 session 生命周期
                        }
                        turnTrace.commit();
                        sink.emit(AgentEvent.completed(finalText, Map.of(
                                "turnNo", state.turnNo(),
                                "iterationCount", state.iterationCount())));
                        return finalText;
                    }

                    state.setTransition(TransitionReason.TOOL_USE);
                    sink.emit(AgentEvent.transition(TransitionReason.TOOL_USE,
                            Map.of("iteration", state.iterationCount())));

                    List<ToolExecutionResult> results = executeToolCalls(outcome.toolCalls(), traceFanout, turnTrace, sink);

                    List<Message> toolResultMessages = results.stream()
                            .map(r -> Message.toolResult(r.toolCallId(), r.content()))
                            .collect(Collectors.toList());
                    messageStore.appendToolResults(toolResultMessages);
                }
            } catch (RuntimeException terminalError) {
                PixFlowException error = toPixFlow(terminalError);
                errorRecorder.record(error);
                try {
                    turnTrace.abort(error);
                } catch (RuntimeException ignored) {
                    // abort 自身的二次异常不影响主异常上抛
                }
                throw terminalError;
            }
        } catch (RuntimeException re) {
            throw re;
        }
    }

    private void applyTransition(RuntimeState state,
                                 GateDecision gate,
                                 AgentEventSink sink,
                                 TraceFanout traceFanout) {
        if (gate instanceof GateDecision.Retry retry) {
            state.setTransition(retry.reason());
            sink.emit(AgentEvent.transition(retry.reason(),
                    Map.of("phase", "retry", "iteration", state.iterationCount())));
            traceFanout.fanoutRetry(retry.reason(), 0L);
        } else if (gate instanceof GateDecision.ContinueAfterAppend cont) {
            state.setTransition(cont.reason());
            sink.emit(AgentEvent.transition(cont.reason(),
                    Map.of("phase", "continueAfterAppend", "iteration", state.iterationCount())));
        } else if (gate instanceof GateDecision.Abort abort) {
            throw abort.error();
        }
    }

    private List<ToolExecutionResult> executeToolCalls(List<com.pixflow.infra.ai.chat.ToolCall> calls,
                                                      TraceFanout traceFanout,
                                                      TurnTrace turnTrace,
                                                      AgentEventSink sink) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        PermissionContext permissionContext = permissionContextFactory.create(state);
        Set<String> hiddenTools = toStringSet(state.metadata().get("hiddenTools"));
        ToolTraceSink loopToolTraceSink = new LoopToolTraceSink(turnTrace);
        ToolExecutionContext context = new ToolExecutionContext(
                permissionPolicy,
                permissionContext,
                hookRegistry,
                resultStorage,
                loopToolTraceSink,
                planModeView,
                toolExecutorService,
                hiddenTools);

        List<ToolCall> harnessCalls = calls.stream()
                .map(this::toHarnessToolCall)
                .collect(Collectors.toList());

        for (ToolCall call : harnessCalls) {
            Map<String, Object> readyMeta = new LinkedHashMap<>(state.metadata());
            if (properties.emitToolInputPreview()) {
                readyMeta.put("inputPreview", String.valueOf(call.arguments()));
            }
            sink.emit(AgentEvent.toolCallReady(call, readyMeta));
            sink.emit(AgentEvent.toolStarted(call, readyMeta));
        }

        List<ToolExecutionResult> results = toolExecutor.execute(harnessCalls, context);

        for (ToolExecutionResult result : results) {
            sink.emit(AgentEvent.toolResult(result, state.metadata()));
            traceFanout.fanoutToolResult(result, 0L);
        }
        return results;
    }

    private ToolCall toHarnessToolCall(com.pixflow.infra.ai.chat.ToolCall infraCall) {
        Map<String, Object> arguments = parseArguments(infraCall.argumentsJson());
        return new ToolCall(
                infraCall.id(),
                infraCall.name(),
                arguments,
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                state.runtimeScope(),
                state.metadata());
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson)) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of("__parseError", String.valueOf(ex.getMessage()), "raw", argumentsJson);
        }
    }

    private ChatRequest toChatRequest(ContextSnapshot snapshot) {
        List<ChatMessage> messages = new ArrayList<>();
        if (snapshot.systemPrompt() != null && !snapshot.systemPrompt().isBlank()) {
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                    List.of(new ChatMessage.TextPart(snapshot.systemPrompt()))));
        }
        for (Message msg : snapshot.messages()) {
            ChatMessage cm = toChatMessage(msg);
            if (cm != null) {
                messages.add(cm);
            }
        }
        List<ToolSchema> schemas = new ArrayList<>();
        for (ToolSchemaView v : snapshot.toolSchemas()) {
            String jsonSchema = jsonMapper.valueToTree(v.schema()).toString();
            schemas.add(new ToolSchema(v.name(), v.description(), jsonSchema));
        }
        Integer maxTokens = maxOutputTokensOverride();
        ChatOptions options = new ChatOptions(null, maxTokens, Duration.ofSeconds(60));
        return new ChatRequest(ModelRole.PRIMARY_CHAT, messages, schemas, null, options);
    }

    private ChatMessage toChatMessage(Message msg) {
        String content = msg.content() == null ? "" : msg.content();
        return switch (msg.role()) {
            case USER -> new ChatMessage(ChatMessage.Role.USER,
                    List.of(new ChatMessage.TextPart(content)));
            case ASSISTANT -> new ChatMessage(ChatMessage.Role.ASSISTANT,
                    List.of(new ChatMessage.TextPart(content)));
            case TOOL_RESULT -> new ChatMessage(ChatMessage.Role.TOOL,
                    List.of(new ChatMessage.TextPart(content)));
            case ATTACHMENT -> new ChatMessage(ChatMessage.Role.USER,
                    List.of(new ChatMessage.TextPart("[attachment] " + content)));
        };
    }

    private Integer maxOutputTokensOverride() {
        Object overrides = state.metadata().get("modelRequestOverrides");
        if (overrides instanceof Map<?, ?> m) {
            Object v = m.get("maxOutputTokens");
            if (v instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    private boolean isContextLimit(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof PixFlowException pf) {
                return pf.category() == ErrorCategory.CONTEXT_LIMIT;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private PixFlowException toPixFlow(Throwable error) {
        if (error instanceof PixFlowException pf) {
            return pf;
        }
        return new PixFlowException(
                com.pixflow.common.error.CommonErrorCode.INTERNAL_ERROR,
                error == null ? "unknown error" : error.getMessage(),
                error);
    }

    private static String previewText(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static Map<String, Object> attachmentsToMap(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Attachment att : attachments) {
            result.put(att.id(), Map.of(
                    "kind", att.kind() == null ? "" : att.kind(),
                    "reference", att.reference() == null ? "" : att.reference(),
                    "metadata", att.metadata()));
        }
        return result;
    }

    private static Set<String> toStringSet(Object value) {
        if (value == null) {
            return Collections.emptySet();
        }
        if (value instanceof Set<?> set) {
            return set.stream().filter(Objects::nonNull).map(String::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof java.util.Collection<?> coll) {
            return coll.stream().filter(Objects::nonNull).map(String::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Collections.emptySet();
    }

    /** 暴露运行时态给测试与外部读取（仅读取）。 */
    public RuntimeState state() {
        return state;
    }
}