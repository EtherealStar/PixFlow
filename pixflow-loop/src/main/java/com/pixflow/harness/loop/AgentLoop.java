package com.pixflow.harness.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.observability.ErrorRecorder;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.engine.ContextEngine;
import com.pixflow.harness.context.model.AssistantToolCall;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.snapshot.ContextSnapshot;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.AssistantMessagePayload;
import com.pixflow.harness.hooks.payload.MessageReferencePayload;
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
import com.pixflow.harness.tools.ToolRuntimeContext;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ToolSchema;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
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
import java.util.stream.Collectors;
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

    private final RuntimeState state;

    private final MessageStore messageStore;

    private final ContextEngine contextEngine;

    private final ContextCompactionService compactionService;

    private final ChatModelClient modelClient;

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
                    ToolExecutor toolExecutor,
                    PermissionPolicy permissionPolicy,
                    ToolResultStorage resultStorage,
                    PlanModeView planModeView,
                    HookRegistry hookRegistry,
                    TraceRecorder traceRecorder,
                    PermissionContextFactory permissionContextFactory,
                    ErrorRecorder errorRecorder,
                    LoopProperties properties,
                    ExecutorService toolExecutorService) {
        this(state, messageStore, contextEngine, compactionService, modelClient,
                toolExecutor, permissionPolicy, resultStorage, planModeView,
                hookRegistry, traceRecorder, permissionContextFactory, errorRecorder, properties,
                new ObjectMapper(), toolExecutorService);
    }

    public AgentLoop(RuntimeState state,
                    MessageStore messageStore,
                    ContextEngine contextEngine,
                    ContextCompactionService compactionService,
                    ChatModelClient modelClient,
                    ToolExecutor toolExecutor,
                    PermissionPolicy permissionPolicy,
                    ToolResultStorage resultStorage,
                    PlanModeView planModeView,
                    HookRegistry hookRegistry,
                    TraceRecorder traceRecorder,
                    PermissionContextFactory permissionContextFactory,
                    ErrorRecorder errorRecorder,
                    LoopProperties properties,
                    ObjectMapper jsonMapper,
                    ExecutorService toolExecutorService) {
        this.state = Objects.requireNonNull(state, "state");
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
        this.contextEngine = Objects.requireNonNull(contextEngine, "contextEngine");
        this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
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
        this.toolExecutorService = Objects.requireNonNull(toolExecutorService, "toolExecutorService");
        this.streamConsumer = new ModelStreamConsumer();
        this.reactiveCompactionGate = new ReactiveCompactionGate(compactionService, this.properties);
        this.outputInterruptHandler = new OutputInterruptHandler(this.properties);
        this.defaultContinuationPrompt = "[continue where you left off; "
                + "the previous response was truncated by output limit]";
    }

    public String stream(String prompt,
                         List<MessageReference> references,
                         AgentEventSink sink,
                         String systemPrompt,
                         List<ToolSchemaView> toolSchemas,
                         CancellationToken cancellation) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancellationRequested();
        state.setTraceId(UUID.randomUUID().toString());
        state.setTurnNo(state.turnNo() + 1);

        UserPromptSubmitPayload upsPayload = new UserPromptSubmitPayload(
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                state.runtimeScope(),
                prompt,
                toHookReferences(references),
                state.metadata());
        hookRegistry.dispatch(HookEvent.USER_PROMPT_SUBMIT, upsPayload);

        cancellation.throwIfCancellationRequested();
        // prompt 与 references 只追加一条 durable USER message，避免滑窗或失败恢复把二者拆开。
        messageStore.appendUser(prompt, references);
        return runLoop(sink, systemPrompt, toolSchemas, cancellation);
    }

    public String continueStream(AgentEventSink sink,
                                 String systemPrompt,
                                 List<ToolSchemaView> toolSchemas,
                                 CancellationToken cancellation) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancellationRequested();
        if (state.traceId() == null || state.traceId().isBlank()) {
            state.setTraceId(UUID.randomUUID().toString());
        }
        state.setTurnNo(state.turnNo() + 1);
        return runLoop(sink, systemPrompt, toolSchemas, cancellation);
    }

    private String runLoop(AgentEventSink sink,
                           String systemPrompt,
                           List<ToolSchemaView> toolSchemas,
                           CancellationToken cancellation) {
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
                    cancellation.throwIfCancellationRequested();
                    state.incrementIteration();
                    int modelTurnIndex = state.iterationCount();
                    String assistantCallId = state.traceId() + ":assistant:" + modelTurnIndex;
                    Map<String, Object> eventMetadata = eventMetadata(assistantCallId, modelTurnIndex);

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
                                    "lastTransition",
                                    state.lastTransition() == null ? "" : state.lastTransition().name())));

                    ChatRequest request = toChatRequest(snapshot);
                    // 模型调用级 retry 只能由 infra/ai 的 ChatModelClient.stream 内部负责。
                    cancellation.throwIfCancellationRequested();
                    Flux<com.pixflow.infra.ai.chat.ChatStreamEvent> flux = modelClient.stream(request);
                    ModelOutcome outcome;
                    try {
                        outcome = streamConsumer.consume(flux, sink, state, eventMetadata, cancellation);
                    } catch (RuntimeException ex) {
                        if (isContextLimit(ex)) {
                            PixFlowException ctxErr = toPixFlow(ex);
                            GateDecision gate = reactiveCompactionGate.onContextLimit(state, messageStore, ctxErr);
                            applyTransition(state, gate, sink, traceFanout, eventMetadata, cancellation);
                            continue;
                        }
                        throw ex;
                    }
                    state.addUsage(outcome.usage());

                    if (outcome.outputInterrupted()) {
                        GateDecision gate = outputInterruptHandler.onOutputInterrupted(
                                state, messageStore, outcome.finalText(), defaultContinuationPrompt);
                        applyTransition(state, gate, sink, traceFanout, eventMetadata, cancellation);
                        continue;
                    }

                    finalText = outcome.finalText() == null ? "" : outcome.finalText();
                    Message assistantMsg = outcome.toolCalls().isEmpty()
                            ? Message.assistant(finalText)
                            : Message.assistantToolCall(finalText, toAssistantToolCalls(outcome.toolCalls()));
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

                    emit(sink,
                            AgentEvent.assistantCompleted(finalText, assistantMsg.id(), eventMetadata),
                            cancellation);

                    if (!outcome.hasToolCalls()) {
                        state.setTransition(TransitionReason.COMPLETED);
                        emit(sink,
                                AgentEvent.transition(TransitionReason.COMPLETED,
                                        eventMetadata(eventMetadata, Map.of(
                                                "iteration", state.iterationCount()))),
                                cancellation);

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
                        emit(sink, AgentEvent.completed(finalText, eventMetadata(eventMetadata, Map.of(
                                "turnNo", state.turnNo(),
                                "iterationCount", state.iterationCount()))), cancellation);
                        return finalText;
                    }

                    state.setTransition(TransitionReason.TOOL_USE);
                    emit(sink, AgentEvent.transition(TransitionReason.TOOL_USE,
                            eventMetadata(eventMetadata, Map.of("iteration", state.iterationCount()))), cancellation);

                    List<ToolExecutionResult> results = executeToolCalls(
                            outcome.toolCalls(), traceFanout, turnTrace, sink, eventMetadata, cancellation);

                    List<Message> toolResultMessages = results.stream()
                            .map(r -> Message.toolResult(r.toolCallId(), r.content()))
                            .collect(Collectors.toList());
                    messageStore.appendToolResults(toolResultMessages);
                }
            } catch (OperationCancelledException cancelled) {
                try {
                    turnTrace.cancel();
                } catch (RuntimeException ignored) {
                }
                try {
                    hookRegistry.dispatch(HookEvent.TURN_STOPPED, new TurnStoppedPayload(
                            state.conversationId(),
                            state.turnNo(),
                            state.traceId(),
                            state.runtimeScope(),
                            "cancelled:" + cancelled.reason().name(),
                            state.metadata()));
                } catch (RuntimeException ignored) {
                }
                throw cancelled;
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
                                 TraceFanout traceFanout,
                                 Map<String, Object> eventMetadata,
                                 CancellationToken cancellation) {
        if (gate instanceof GateDecision.Retry retry) {
            state.setTransition(retry.reason());
            emit(sink,
                    AgentEvent.transition(retry.reason(), eventMetadata(eventMetadata, Map.of(
                            "phase", "retry",
                            "iteration", state.iterationCount()))),
                    cancellation);
            traceFanout.fanoutRetry(retry.reason(), 0L);
        } else if (gate instanceof GateDecision.ContinueAfterAppend cont) {
            state.setTransition(cont.reason());
            emit(sink,
                    AgentEvent.transition(cont.reason(), eventMetadata(eventMetadata, Map.of(
                            "phase", "continueAfterAppend",
                            "iteration", state.iterationCount()))),
                    cancellation);
        } else if (gate instanceof GateDecision.Abort abort) {
            throw abort.error();
        }
    }

    private List<ToolExecutionResult> executeToolCalls(List<com.pixflow.infra.ai.chat.ToolCall> calls,
                                                      TraceFanout traceFanout,
                                                      TurnTrace turnTrace,
                                                      AgentEventSink sink,
                                                      Map<String, Object> eventMetadata,
                                                      CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        PermissionContext permissionContext = permissionContextFactory.create(state);
        Set<String> hiddenTools = toStringSet(state.metadata().get("hiddenTools"));
        ToolTraceSink loopToolTraceSink = new LoopToolTraceSink(turnTrace);
        ToolRuntimeContext runtimeContext = new ToolRuntimeContext() {
            @Override
            public Map<String, Object> metadata() {
                return state.metadata();
            }

            @Override
            public void putMetadata(String key, Object value) {
                state.putMetadata(key, value);
            }
        };
        ToolExecutionContext context = new ToolExecutionContext(
                permissionPolicy,
                permissionContext,
                hookRegistry,
                resultStorage,
                loopToolTraceSink,
                planModeView,
                runtimeContext,
                toolExecutorService,
                hiddenTools,
                cancellation);

        List<ParsedToolCall> parsedCalls = calls.stream()
                .map(this::parseToolCall)
                .toList();
        List<ToolCall> harnessCalls = parsedCalls.stream()
                .map(ParsedToolCall::toolCall)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        for (ToolCall call : harnessCalls) {
            cancellation.throwIfCancellationRequested();
            Map<String, Object> readyMeta = new LinkedHashMap<>(eventMetadata);
            if (properties.emitToolInputPreview()) {
                readyMeta.put("inputPreview", String.valueOf(call.arguments()));
            }
            emit(sink, AgentEvent.toolCallReady(call, readyMeta), cancellation);
            emit(sink, AgentEvent.toolStarted(call, readyMeta), cancellation);
        }

        List<ToolExecutionResult> executedResults = harnessCalls.isEmpty()
                ? List.of()
                : toolExecutor.execute(harnessCalls, context);
        List<ToolExecutionResult> results = mergeToolResults(parsedCalls, executedResults);

        for (ToolExecutionResult result : results) {
            emit(sink, AgentEvent.toolResult(result, eventMetadata), cancellation);
        }
        return results;
    }

    private static void emit(AgentEventSink sink, AgentEvent event, CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        sink.emit(event);
        cancellation.throwIfCancellationRequested();
    }

    private Map<String, Object> eventMetadata(String assistantCallId, int modelTurnIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>(state.metadata());
        // SSE timeline 归属字段只用于前端实时渲染，不写回 RuntimeState.metadata。
        metadata.put("assistantCallId", assistantCallId);
        metadata.put("modelTurnIndex", modelTurnIndex);
        metadata.put("iteration", state.iterationCount());
        metadata.put("traceId", state.traceId());
        metadata.put("turnNo", state.turnNo());
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> eventMetadata(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (base != null) {
            metadata.putAll(base);
        }
        if (extra != null) {
            metadata.putAll(extra);
        }
        return Map.copyOf(metadata);
    }

    private static List<ToolExecutionResult> mergeToolResults(List<ParsedToolCall> parsedCalls,
                                                              List<ToolExecutionResult> executedResults) {
        if (parsedCalls == null || parsedCalls.isEmpty()) {
            return List.of();
        }
        Map<String, ToolExecutionResult> byCallId = new LinkedHashMap<>();
        if (executedResults != null) {
            for (ToolExecutionResult result : executedResults) {
                if (result == null || result.toolCallId() == null || result.toolCallId().isBlank()) {
                    continue;
                }
                // 重复 tool_call_id 只采纳第一条，避免后到结果覆盖 provider 原始顺序。
                byCallId.putIfAbsent(result.toolCallId(), result);
            }
        }
        List<ToolExecutionResult> merged = new ArrayList<>(parsedCalls.size());
        for (ParsedToolCall parsed : parsedCalls) {
            if (parsed.parseError() != null) {
                merged.add(parsed.parseError());
            } else if (parsed.toolCall() != null) {
                ToolExecutionResult result = byCallId.get(parsed.toolCall().toolCallId());
                if (result != null) {
                    merged.add(result);
                } else {
                    merged.add(missingToolResult(parsed.toolCall()));
                }
            }
        }
        return merged;
    }

    private static ToolExecutionResult missingToolResult(ToolCall call) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorCategory", "INTERNAL");
        metadata.put("recovery", "SKIP");
        metadata.put("missingToolResult", true);
        return ToolExecutionResult.error(
                call.toolCallId(),
                call.toolName(),
                "tool_execution_missing_result: executor returned no result for tool call",
                metadata);
    }

    private ParsedToolCall parseToolCall(com.pixflow.infra.ai.chat.ToolCall infraCall) {
        ParsedArguments parsedArguments = parseArguments(infraCall.argumentsJson());
        if (parsedArguments.error() != null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("errorCategory", "VALIDATION");
            metadata.put("recovery", "SKIP");
            metadata.put("parseError", parsedArguments.error());
            metadata.put("rawLength", infraCall.argumentsJson() == null ? 0 : infraCall.argumentsJson().length());
            return new ParsedToolCall(null, ToolExecutionResult.error(
                    infraCall.id(),
                    infraCall.name(),
                    "invalid_tool_input: arguments must be valid JSON object",
                    metadata));
        }
        ToolCall toolCall = new ToolCall(
                infraCall.id(),
                infraCall.name(),
                parsedArguments.arguments(),
                state.conversationId(),
                state.turnNo(),
                state.traceId(),
                state.runtimeScope(),
                state.metadata());
        return new ParsedToolCall(toolCall, null);
    }

    private ParsedArguments parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson)) {
            return new ParsedArguments(Map.of(), null);
        }
        try {
            Map<String, Object> arguments = jsonMapper.readValue(
                    argumentsJson,
                    new TypeReference<Map<String, Object>>() { });
            return new ParsedArguments(arguments, null);
        } catch (JsonProcessingException ex) {
            return new ParsedArguments(Map.of(), ex.getOriginalMessage());
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
        return new ChatRequest(ModelRole.PRIMARY_CHAT, messages, schemas, null, options, null);
    }

    private ChatMessage toChatMessage(Message msg) {
        String content = msg.content() == null ? "" : msg.content();
        return switch (msg.role()) {
            case USER -> new ChatMessage(ChatMessage.Role.USER,
                    List.of(new ChatMessage.TextPart(renderUserContent(msg, content))));
            case ASSISTANT -> assistantChatMessage(msg, content);
            case TOOL_RESULT -> new ChatMessage(ChatMessage.Role.TOOL,
                    List.of(new ChatMessage.ToolResultPart(
                            msg.toolCallId(),
                            content.isBlank() ? "[empty tool result]" : content)));
        };
    }

    private ChatMessage assistantChatMessage(Message msg, String content) {
        List<AssistantToolCall> toolCalls = msg.metadata().assistantToolCalls();
        if (toolCalls.isEmpty()) {
            return new ChatMessage(ChatMessage.Role.ASSISTANT,
                    List.of(new ChatMessage.TextPart(content)));
        }
        List<ChatMessage.Part> parts = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            parts.add(new ChatMessage.TextPart(content));
        }
        // assistant tool-call 消息允许没有文本，但必须保留完整工具调用载荷。
        for (AssistantToolCall toolCall : toolCalls) {
            parts.add(new ChatMessage.ToolCallPart(
                    toolCall.id(),
                    toolCall.name(),
                    toolCall.argumentsJson()));
        }
        return new ChatMessage(ChatMessage.Role.ASSISTANT, parts);
    }

    private static List<AssistantToolCall> toAssistantToolCalls(List<com.pixflow.infra.ai.chat.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
                .map(call -> new AssistantToolCall(call.id(), call.name(), call.argumentsJson()))
                .toList();
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

    private static List<MessageReferencePayload> toHookReferences(List<MessageReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        return references.stream()
                .map(reference -> new MessageReferencePayload(
                        reference.referenceKey(), reference.displayPathSnapshot()))
                .toList();
    }

    private static String renderUserContent(Message message, String content) {
        List<MessageReference> references = message.metadata().references();
        if (references.isEmpty()) {
            return content;
        }
        StringBuilder rendered = new StringBuilder(content);
        if (!content.isBlank()) {
            rendered.append("\n\n");
        }
        rendered.append("Referenced materials:");
        for (MessageReference reference : references) {
            rendered.append("\n- ")
                    .append(reference.displayPathSnapshot())
                    .append(" [")
                    .append(reference.referenceKey())
                    .append(']');
        }
        return rendered.toString();
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

    private record ParsedArguments(Map<String, Object> arguments, String error) {
    }

    private record ParsedToolCall(ToolCall toolCall, ToolExecutionResult parseError) {
    }
}
