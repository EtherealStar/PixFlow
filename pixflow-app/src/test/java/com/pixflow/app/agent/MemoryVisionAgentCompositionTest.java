package com.pixflow.app.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.agent.AgentOrchestrator;
import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.memory.MemoryRecallPlanner;
import com.pixflow.agent.planmode.PlanModeController;
import com.pixflow.agent.prompt.CaffeinePromptSectionCache;
import com.pixflow.agent.prompt.DynamicPromptAssembler;
import com.pixflow.agent.prompt.sections.LongTermMemorySection;
import com.pixflow.agent.prompt.sections.PreferenceSection;
import com.pixflow.agent.sessionmemory.SessionMemoryService;
import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.tools.DefaultToolRegistry;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.MemoryService;
import com.pixflow.module.memory.config.MemoryAutoConfiguration;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.preference.UserPreferenceMapper;
import com.pixflow.module.memory.skuhistory.SkuHistoryMapper;
import com.pixflow.module.file.api.AssetReferenceExpander;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.memory.context.MemorySection;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import com.pixflow.module.vision.api.VisualFactsLookupResult;
import com.pixflow.module.vision.api.VisualFactsLookupStatus;
import com.pixflow.module.vision.tool.ProductVisualFactsTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class MemoryVisionAgentCompositionTest {

    @Test
    void productionMemoryGraphRequiresFileOwnerBeansAndBuildsWithoutVector() {
        memoryContextRunner()
                .withBean(AssetReferenceResolver.class, () -> mock(AssetReferenceResolver.class))
                .withBean(AssetReferenceExpander.class, () -> mock(AssetReferenceExpander.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MemoryService.class);
                });

        memoryContextRunner().run(context -> assertThat(context).hasFailed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticMemoryAndExplicitVisualFactsRemainIndependentInOneTurn() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger visualLookups = new AtomicInteger();
        ToolDescriptor visualTool = ProductVisualFactsTool.descriptor(referenceKey -> {
            visualLookups.incrementAndGet();
            return new VisualFactsLookupResult(
                    VisualFactsLookupStatus.AVAILABLE,
                    referenceKey,
                    List.of(),
                    false,
                    "facts-ready");
        }, objectMapper);
        ToolRegistry registry = new DefaultToolRegistry(List.of(visualTool), null);

        AgentProperties properties = new AgentProperties();
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.prepareContext(any())).thenReturn(memoryContext());
        MemoryRecallPlanner memoryPlanner = new MemoryRecallPlanner(memoryService, properties);
        SessionMemoryService sessionMemory = mock(SessionMemoryService.class);
        when(sessionMemory.load("conversation-1")).thenReturn(Optional.empty());
        DynamicPromptAssembler assembler = new DynamicPromptAssembler(
                List.of(new PreferenceSection(), new LongTermMemorySection()),
                new CaffeinePromptSectionCache(properties));

        List<ChatRequest> requests = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModelClient streamingModel = new ChatModelClient() {
            @Override
            public com.pixflow.infra.ai.chat.ChatResult call(ChatRequest request) {
                throw new UnsupportedOperationException("stream only");
            }

            @Override
            public Flux<ChatStreamEvent> stream(ChatRequest request) {
                requests.add(request);
                if (modelCalls.getAndIncrement() == 0) {
                    return Flux.just(new ChatStreamEvent.Completed(
                            "",
                            List.of(new ToolCall(
                                    "visual-call-1",
                                    "get_product_visual_facts",
                                    "{\"referenceKey\":\"image:42\"}")),
                            StopReason.TOOL_CALLS,
                            new TokenUsage(10, 3, 13)));
                }
                return Flux.just(new ChatStreamEvent.Completed(
                        "视觉事实已读取",
                        List.of(),
                        StopReason.STOP,
                        new TokenUsage(10, 3, 13)));
            }
        };

        var toolExecutor = (com.pixflow.harness.tools.ToolExecutor) (calls, context) -> calls.stream()
                .map(call -> {
                    ToolDescriptor descriptor = registry.get(call.toolName()).orElseThrow();
                    String content = descriptor.handler().handle(new ToolInvocation(
                            call.toolCallId(),
                            call.toolName(),
                            call.arguments(),
                            call.conversationId(),
                            call.turnNo(),
                            call.traceId(),
                            call.runtimeScope(),
                            call.metadata())).content();
                    long now = System.currentTimeMillis();
                    // 通过 loop 提供的 sink 投影工具 trace，保持 Memory recall trace 与工具 trace 分离。
                    context.traceSink().record(new com.pixflow.harness.tools.result.ToolTraceSink.ToolTraceEvent(
                            call.toolName(), call.toolCallId(), now, now, false, null,
                            false, false, Map.of("source", "vision")));
                    return ToolExecutionResult.success(call.toolCallId(), call.toolName(), content, Map.of());
                })
                .toList();

        TurnTrace turnTrace = mock(TurnTrace.class);
        TraceRecorder traceRecorder = (conversationId, turnNo, traceId, scope) -> turnTrace;
        HookRegistry hooks = (event, payload) -> HookResult.noop();
        ExecutorService loopExecutor = Executors.newSingleThreadExecutor();
        try {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    memoryPlanner,
                    sessionMemory,
                    assembler,
                    registry,
                    new PlanModeController(properties),
                    properties,
                    new LoopProperties(),
                    objectMapper,
                    null,
                    streamingModel,
                    toolExecutor,
                    hooks,
                    traceRecorder,
                    error -> { },
                    new DefaultPermissionContextFactory(),
                    null,
                    null,
                    null,
                    loopExecutor,
                    mock(ObjectProvider.class),
                    mock(ObjectProvider.class));

            String result = orchestrator.streamNewTurn(new AgentTurnRequest(
                    "conversation-1",
                    new PermissionPrincipal("user-1", "tester"),
                    "根据商品图给出建议",
                    List.of(),
                    CancellationToken.NONE), AgentEventSink.NOOP);

            assertThat(result).isEqualTo("视觉事实已读取");
            assertThat(requests).hasSize(2);
            assertThat(systemText(requests.getFirst())).contains("商品主体不得裁切");
            assertThat(requests.getFirst().toolSchemas())
                    .extracting(schema -> schema.name())
                    .containsExactly("get_product_visual_facts");
            String toolResult = toolResultText(requests.get(1));
            assertThat(toolResult).contains("AVAILABLE", "image:42", "facts-ready");
            assertThat(toolResult).doesNotContain(
                    "lastWriter", "provider", "prompt", "attempt", "operationalMetadata");
            assertThat(visualLookups).hasValue(1);
            verify(memoryService, times(1)).prepareContext(any());
            verify(turnTrace, times(1)).recordRecall(any());
            verify(turnTrace, atLeastOnce()).recordToolCall(any());
        } finally {
            loopExecutor.shutdownNow();
        }
    }

    private static MemoryContext memoryContext() {
        MemoryItem item = new MemoryItem(
                "preference-1",
                MemoryType.PREFERENCE,
                "商品主体不得裁切",
                "user_preference",
                "",
                "",
                1.0,
                0.0,
                1.0,
                1.0,
                1.0,
                null,
                null,
                Map.of());
        MemorySection section = new MemorySection(
                "user_preferences",
                "- 商品主体不得裁切",
                List.of(item),
                12,
                Map.of("requested_item_count", 1));
        return new MemoryContext(
                "conversation-1",
                1,
                List.of(section),
                Map.of("token_budget", 4000, "used_tokens", 12),
                false);
    }

    private static ApplicationContextRunner memoryContextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MemoryAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(UserPreferenceMapper.class, () -> mock(UserPreferenceMapper.class))
                .withBean(SkuHistoryMapper.class, () -> mock(SkuHistoryMapper.class))
                .withBean(InsightDocMapper.class, () -> mock(InsightDocMapper.class));
    }

    private static String systemText(ChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.SYSTEM)
                .flatMap(message -> message.parts().stream())
                .filter(ChatMessage.TextPart.class::isInstance)
                .map(ChatMessage.TextPart.class::cast)
                .map(ChatMessage.TextPart::text)
                .findFirst()
                .orElseThrow();
    }

    private static String toolResultText(ChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.TOOL)
                .flatMap(message -> message.parts().stream())
                .filter(ChatMessage.ToolResultPart.class::isInstance)
                .map(ChatMessage.ToolResultPart.class::cast)
                .map(ChatMessage.ToolResultPart::content)
                .findFirst()
                .orElseThrow();
    }
}
