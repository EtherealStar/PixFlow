package com.pixflow.harness.loop.trace;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.loop.MetadataValues;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.tools.result.ToolTraceSink;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        Instant spanStartedAt = Instant.now();
        if (result == null) {
            resultSummary = "noop";
        } else {
            resultSummary = new LinkedHashMap<String, Object>() {
                {
                    put("blocked", result.blocked());
                    put("inputRewritten", result.inputRewritten());
                    put("blockingReason", result.blockingReason());
                    put("metadata", MetadataValues.immutableCopy(result.metadata()));
                }
            };
            if (result.blocked()) {
                error = new TraceError(
                        spanStartedAt,
                        "HOOK_BLOCKED",
                        "VALIDATION",
                        "SKIP",
                        result.blockingReason(),
                        null,
                        MetadataValues.immutableCopy(result.metadata()));
            }
        }
        long safeLatencyMs = Math.max(0L, latencyMs);
        TraceToolCall span = new TraceToolCall(
                spanStartedAt,
                "loop.hook." + event.name().toLowerCase(Locale.ROOT),
                input,
                resultSummary,
                null,
                "hook",
                "ALLOW",
                safeLatencyMs,
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
     * tools 模块的工具 trace 事件单条转投到当前 TurnTrace。
     */
    public void fanoutToolTraceEvent(ToolTraceSink.ToolTraceEvent event) {
        if (event == null) {
            return;
        }
        long started = event.startedAtMillis();
        long finished = event.finishedAtMillis();
        Map<String, Object> metadata = new LinkedHashMap<>(MetadataValues.immutableCopy(event.metadata()));
        if (started <= 0L && finished <= 0L) {
            long now = Instant.now().toEpochMilli();
            started = now;
            finished = now;
            metadata.put("timestampCorrected", true);
        } else if (started <= 0L) {
            started = finished;
            metadata.put("timestampCorrected", true);
        } else if (finished <= 0L) {
            finished = started;
            metadata.put("timestampCorrected", true);
        } else if (finished < started) {
            finished = started;
            metadata.put("timestampCorrected", true);
        }
        String toolName = event.toolName();
        if (toolName == null || toolName.isBlank()) {
            toolName = "unknown_tool";
            metadata.put("toolNameMissing", true);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("toolName", toolName);
        input.put("toolCallId", event.toolCallId());
        input.put("startedAtMillis", started);
        input.put("finishedAtMillis", finished);
        input.put("rewritten", event.rewritten());
        input.put("resultExternalized", event.resultExternalized());
        if (event.errorCategory() != null) {
            input.put("errorCategory", event.errorCategory());
        }
        Map<String, Object> output = new LinkedHashMap<>(metadata);
        output.put("error", event.error());
        TraceError traceError = event.error() ? new TraceError(
                Instant.ofEpochMilli(finished),
                "TOOL_FAILURE",
                event.errorCategory() == null ? "TOOL" : event.errorCategory(),
                "SKIP",
                "tool failed",
                null,
                metadata) : null;
        TraceToolCall span = new TraceToolCall(
                Instant.ofEpochMilli(started),
                toolName,
                input,
                output,
                null,
                "tool",
                "ALLOW",
                finished - started,
                traceError);
        turnTrace.recordToolCall(span);
    }
}
