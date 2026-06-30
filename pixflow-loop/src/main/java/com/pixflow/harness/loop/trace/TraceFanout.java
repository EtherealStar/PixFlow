package com.pixflow.harness.loop.trace;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.harness.tools.result.ToolTraceSink;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * trace 转投器：把 context / hooks / tools 的可观测数据统一写到当前 {@link TurnTrace}。
 *
 * <p>trace 责任在 loop：context / hooks / tools 模块不依赖 eval，它们的可观测数据
 * 由 loop 在边界收集后经本类转投。运行时通过 try-with-resources 由 AgentLoop 持有。
 */
public final class TraceFanout {

    private final TurnTrace turnTrace;

    public TraceFanout(TurnTrace turnTrace) {
        this.turnTrace = Objects.requireNonNull(turnTrace, "turnTrace");
    }

    /**
     * 把 {@code BuildResult.pruneEntries}（cheap pipeline + destructive compaction 裁剪日志）
     * 转投到 {@code TurnTrace.recordPrune}。
     */
    public void fanoutPrune(List<TracePruneEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (TracePruneEntry entry : entries) {
            turnTrace.recordPrune(entry);
        }
    }

    /**
     * 把 hooks 派发结果转投为一条 {@code recordToolCall(name="loop.hook.span")}，
     * 便于离线 rubric 检查 hookErrors / inputRewritten / blockingReason。
     */
    public void fanoutHookSpan(HookEvent event,
                               HookResult result,
                               String toolName,
                               String toolCallId,
                               long latencyMs) {
        if (event == null) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("event", event.name());
        if (toolName != null) input.put("toolName", toolName);
        if (toolCallId != null) input.put("toolCallId", toolCallId);
        Object resultSummary;
        TraceError error = null;
        if (result == null) {
            resultSummary = "noop";
        } else {
            resultSummary = new LinkedHashMap<String, Object>() {
                {
                    put("blocked", result.blocked());
                    put("inputRewritten", result.inputRewritten());
                    put("blockingReason", result.blockingReason());
                    put("metadata", result.metadata());
                }
            };
            if (result.blocked()) {
                error = new TraceError(
                        Instant.now(),
                        "HOOK_BLOCKED",
                        "PERMISSION",
                        "TERMINATE",
                        result.blockingReason(),
                        null,
                        result.metadata());
            }
        }
        TraceToolCall span = new TraceToolCall(
                Instant.now(),
                "loop.hook." + event.name().toLowerCase(),
                input,
                resultSummary,
                null,
                "hook",
                "ALLOW",
                latencyMs,
                error);
        turnTrace.recordToolCall(span);
    }

    /**
     * 把每次 retry 转投为一条 recordToolCall(name="loop.retry")。
     */
    public void fanoutRetry(TransitionReason reason, long latencyMs) {
        if (reason == null) {
            return;
        }
        TraceToolCall span = new TraceToolCall(
                Instant.now(),
                "loop.retry",
                Map.of("reason", reason.name()),
                reason.name(),
                null,
                "retry",
                "ALLOW",
                latencyMs,
                null);
        turnTrace.recordToolCall(span);
    }

    /**
     * 工具执行结果转投（用于主循环显式记录单条工具调用，比 ToolTraceSink 更高一层语义）。
     */
    public void fanoutToolResult(ToolExecutionResult result, long latencyMs) {
        if (result == null) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("toolName", result.toolName());
        input.put("toolCallId", result.toolCallId());
        Object payload = result.error() ? result.metadata() : result.content();
        TraceError error = null;
        if (result.error()) {
            error = new TraceError(
                    Instant.now(),
                    "TOOL_ERROR",
                    "TOOL",
                    "SKIP",
                    result.content(),
                    null,
                    result.metadata());
        }
        TraceToolCall span = new TraceToolCall(
                Instant.now(),
                result.toolName(),
                input,
                payload,
                null,
                "tool",
                "ALLOW",
                latencyMs,
                error);
        turnTrace.recordToolCall(span);
    }

    /**
     * tools 模块的工具 trace 事件单条转投到当前 TurnTrace。
     */
    public void fanoutToolTraceEvent(ToolTraceSink.ToolTraceEvent event) {
        if (event == null) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("toolName", event.toolName());
        input.put("toolCallId", event.toolCallId());
        input.put("startedAtMillis", event.startedAtMillis());
        input.put("finishedAtMillis", event.finishedAtMillis());
        input.put("rewritten", event.rewritten());
        input.put("resultExternalized", event.resultExternalized());
        if (event.errorCategory() != null) {
            input.put("errorCategory", event.errorCategory());
        }
        Map<String, Object> output = new LinkedHashMap<>(event.metadata());
        output.put("error", event.error());
        TraceError traceError = event.error() ? new TraceError(
                Instant.ofEpochMilli(event.finishedAtMillis()),
                "TOOL_FAILURE",
                event.errorCategory() == null ? "TOOL" : event.errorCategory(),
                "SKIP",
                "tool failed",
                null,
                event.metadata()) : null;
        TraceToolCall span = new TraceToolCall(
                Instant.ofEpochMilli(event.startedAtMillis()),
                event.toolName(),
                input,
                output,
                null,
                "tool",
                "ALLOW",
                event.finishedAtMillis() - event.startedAtMillis(),
                traceError);
        turnTrace.recordToolCall(span);
    }
}