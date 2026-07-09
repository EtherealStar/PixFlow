package com.pixflow.harness.loop.trace;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.loop.MetadataValues;
import com.pixflow.harness.tools.result.ToolTraceSink;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把 tools 执行管线的 {@link ToolTraceSink.ToolTraceEvent} 单条适配为
 * {@code TurnTrace.recordToolCall(TraceToolCall)}。
 *
 * <p>这样 tools 模块不需要反向依赖 eval，trace 责任在 loop —— 上游
 * {@code ToolExecutionContext} 仅持有 {@link ToolTraceSink} SPI，loop 在构造
 * 上下文时把本类实例注入。
 *
 * <p>{@code event.rewritten} 标志会反映到 {@code TraceToolCall.metadata("rewritten", true)}，
 * 供 rubric / 离线评测识别「input 被 hook 改写」事件。
 */
public final class LoopToolTraceSink implements ToolTraceSink {

    private final TurnTrace turnTrace;

    public LoopToolTraceSink(TurnTrace turnTrace) {
        this.turnTrace = Objects.requireNonNull(turnTrace, "turnTrace");
    }

    @Override
    public void record(ToolTraceEvent event) {
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
        input.put("rewritten", event.rewritten());
        input.put("resultExternalized", event.resultExternalized());
        Map<String, Object> output = new LinkedHashMap<>(metadata);
        output.put("error", event.error());

        TraceError traceError = null;
        if (event.error()) {
            traceError = new TraceError(
                    Instant.ofEpochMilli(finished),
                    "TOOL_FAILURE",
                    event.errorCategory() == null ? "TOOL" : event.errorCategory(),
                    "SKIP",
                    "tool failed",
                    null,
                    metadata);
        }
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
