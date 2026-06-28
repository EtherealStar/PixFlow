package com.pixflow.harness.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.pixflow.harness.hooks.config.HookProperties;
import com.pixflow.harness.hooks.error.HookError;
import com.pixflow.harness.hooks.payload.CompactionPayload;
import com.pixflow.harness.hooks.payload.HookPayload;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.hooks.payload.ToolUsePayload;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultHookRegistryTest {

    @Test
    void dispatchOrdersCallbacksAndStopsOnBlockingReason() {
        List<String> calls = new ArrayList<>();
        HookRegistry registry = registry(
                callback(10, event -> {
                    calls.add("third");
                    return HookResult.withMetadata(Map.of("third", true));
                }),
                callback(-10, event -> {
                    calls.add("first");
                    return HookResult.withMetadata(Map.of("first", true));
                }),
                callback(0, event -> {
                    calls.add("second");
                    return HookResult.block("invalid dag");
                }));

        HookResult result = registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of()));

        assertThat(calls).containsExactly("first", "second");
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReason()).isEqualTo("invalid dag");
        assertThat(result.metadata()).containsEntry("first", true);
        assertThat(result.metadata()).doesNotContainKey("third");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchIsolatesCallbackErrorsAndContinuesByDefault() {
        HookRegistry registry = registry(
                callback(-1, event -> {
                    throw new IllegalStateException("Bearer secret-token at D:\\secret\\file.txt");
                }),
                callback(0, event -> HookResult.withMetadata(Map.of("afterError", true))));

        HookResult result = registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of()));

        assertThat(result.metadata()).containsEntry("afterError", true);
        Object hookErrors = result.metadata().get("hookErrors");
        assertThat(hookErrors).isInstanceOf(HookError.class);
        HookError error = (HookError) hookErrors;
        assertThat(error.category()).isEqualTo("INTERNAL");
        assertThat(error.safeMessage()).contains("Bearer ***");
        assertThat(error.safeMessage()).contains("<external>");
        assertThat(error.safeMessage()).doesNotContain("secret-token");
    }

    @Test
    void dispatchCanFailFastOnCallbackErrorForDebugging() {
        HookProperties properties = new HookProperties();
        properties.setFailFastOnCallbackError(true);
        HookRegistry registry = new DefaultHookRegistry(List.of(callback(0, event -> {
            throw new IllegalArgumentException("bad hook");
        })), properties);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of())));
    }

    @Test
    void dispatchStillContinuesWhenCallbackErrorNormalizationFails() {
        HookRegistry registry = registry(
                callback(-1, event -> {
                    throw new BadMessageException();
                }),
                callback(0, event -> HookResult.withMetadata(Map.of("afterNormalizationFailure", true))));

        HookResult result = registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of()));

        assertThat(result.metadata()).containsEntry("afterNormalizationFailure", true);
        Object hookErrors = result.metadata().get("hookErrors");
        assertThat(hookErrors).isInstanceOf(HookError.class);
        HookError error = (HookError) hookErrors;
        assertThat(error.category()).isEqualTo("INTERNAL");
        assertThat(error.safeMessage()).contains("Failed to normalize callback error");
    }

    @Test
    void runtimeScopeRequiresSubagentTypeWhenSubagentIsTrue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RuntimeScope.of(null));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RuntimeScope(true, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preToolUseAppliesShallowPatchAndPropagatesNewPayload() {
        Map<String, Object> originalNested = new LinkedHashMap<>();
        originalNested.put("a", 1);
        List<Map<String, Object>> seenInputs = new ArrayList<>();
        HookRegistry registry = registry(
                callback(-1, event -> HookResult.rewrite(Map.of(
                        "format", "webp",
                        "quality", 80,
                        "nested", Map.of("b", 2)))),
                payloadCallback(0, payload -> {
                    seenInputs.add(((ToolUsePayload) payload).toolInput());
                    return HookResult.noop();
                }));

        HookResult result = registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of(
                "width", 800,
                "format", "png",
                "nested", originalNested)));

        assertThat(seenInputs).hasSize(1);
        assertThat(seenInputs.getFirst()).containsEntry("width", 800)
                .containsEntry("format", "webp")
                .containsEntry("quality", 80)
                .containsEntry("nested", Map.of("b", 2));
        assertThat((Map<String, Object>) seenInputs.getFirst().get("nested")).doesNotContainKey("a");
        assertThat(result.updatedInput()).containsEntry("format", "webp")
                .containsEntry("quality", 80)
                .containsEntry("nested", Map.of("b", 2));
    }

    @Test
    void nonPreToolUseDoesNotApplyUpdatedInputToPayload() {
        List<Map<String, Object>> seenInputs = new ArrayList<>();
        HookRegistry registry = registry(
                callback(-1, event -> HookResult.rewrite(Map.of("format", "webp"))),
                payloadCallback(0, payload -> {
                    seenInputs.add(((ToolUsePayload) payload).toolInput());
                    return HookResult.noop();
                }));

        HookResult result = registry.dispatch(HookEvent.POST_TOOL_USE, toolPayload(Map.of("format", "png")));

        assertThat(seenInputs).singleElement().satisfies(input ->
                assertThat(input).containsEntry("format", "png"));
        assertThat(result.updatedInput()).containsEntry("format", "webp");
    }

    @Test
    void metadataConflictsCollapseToListsAndProtectReservedHookErrorsKey() {
        HookRegistry registry = registry(
                callback(-1, event -> HookResult.withMetadata(Map.of(
                        "dag.validationWarnings", "missing expected_count",
                        "hookErrors", "fake"))),
                callback(0, event -> HookResult.withMetadata(Map.of(
                        "dag.validationWarnings", "unsafe resize",
                        "hookErrors", "fake2"))));

        HookResult result = registry.dispatch(HookEvent.PRE_TOOL_USE, toolPayload(Map.of()));

        assertThat(result.metadata())
                .containsEntry("dag.validationWarnings", List.of("missing expected_count", "unsafe resize"))
                .containsEntry("callbackMetadata.hookErrors", List.of("fake", "fake2"));
        assertThat(result.metadata()).doesNotContainKey("hookErrors");
    }

    @Test
    void preCompactInstructionsAreAccumulatedThroughMetadata() {
        HookRegistry registry = registry(
                callback(-1, event -> HookResult.withMetadata(Map.of("compact.summaryInstructions", "keep user goal"))),
                callback(0, event -> HookResult.withMetadata(Map.of("compact.summaryInstructions", "keep task state"))));

        HookResult result = registry.dispatch(HookEvent.PRE_COMPACT, compactionPayload());

        assertThat(result.metadata())
                .containsEntry("compact.summaryInstructions", List.of("keep user goal", "keep task state"));
        assertThat(result.updatedInput()).isEmpty();
    }

    @Test
    void hookCanSelfGateSubagentRuntime() {
        HookRegistry registry = registry(payloadCallback(0, payload -> {
            if (payload.runtime().subagent()) {
                return HookResult.noop();
            }
            return HookResult.block("main only");
        }));

        HookResult subagentResult = registry.dispatch(HookEvent.PRE_TOOL_USE,
                toolPayload(RuntimeScope.of("vision"), Map.of()));
        HookResult mainResult = registry.dispatch(HookEvent.PRE_TOOL_USE,
                toolPayload(RuntimeScope.main(), Map.of()));

        assertThat(subagentResult.blocked()).isFalse();
        assertThat(mainResult.blockingReason()).isEqualTo("main only");
    }

    @Test
    void payloadsDefensivelyCopyInputMaps() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("format", "png");
        ToolUsePayload payload = toolPayload(input);
        input.put("format", "webp");

        assertThat(payload.toolInput()).containsEntry("format", "png");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> payload.toolInput().put("quality", 80));
    }

    private static HookRegistry registry(HookCallback... callbacks) {
        return new DefaultHookRegistry(List.of(callbacks), new HookProperties());
    }

    private static HookCallback callback(int order, CallbackBody body) {
        return new HookCallback() {
            @Override
            public Set<HookEvent> supportedEvents() {
                return Set.of(HookEvent.PRE_TOOL_USE, HookEvent.POST_TOOL_USE, HookEvent.PRE_COMPACT);
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public HookResult handle(HookEvent event, HookPayload payload) {
                return body.handle(event);
            }
        };
    }

    private static HookCallback payloadCallback(int order, PayloadCallbackBody body) {
        return new HookCallback() {
            @Override
            public Set<HookEvent> supportedEvents() {
                return Set.of(HookEvent.PRE_TOOL_USE, HookEvent.POST_TOOL_USE);
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public HookResult handle(HookEvent event, HookPayload payload) {
                return body.handle(payload);
            }
        };
    }

    private static ToolUsePayload toolPayload(Map<String, Object> input) {
        return toolPayload(RuntimeScope.main(), input);
    }

    private static ToolUsePayload toolPayload(RuntimeScope runtime, Map<String, Object> input) {
        return new ToolUsePayload(
                "conversation-1",
                1,
                "trace-1",
                runtime,
                HookEvent.PRE_TOOL_USE,
                "compile_dag",
                "call-1",
                input,
                PermissionDecision.allow("compile_dag", PermissionSource.DEFAULT_ALLOW),
                Map.of());
    }

    private static CompactionPayload compactionPayload() {
        return new CompactionPayload(
                "conversation-1",
                1,
                "trace-1",
                RuntimeScope.main(),
                "AUTO",
                1000,
                500,
                null,
                Map.of());
    }

    private interface CallbackBody {
        HookResult handle(HookEvent event);
    }

    private interface PayloadCallbackBody {
        HookResult handle(HookPayload payload);
    }

    private static final class BadMessageException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("message unavailable");
        }
    }
}
