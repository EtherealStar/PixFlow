package com.pixflow.harness.loop.trace;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TraceToolCall;
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
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("toolName", event.toolName());
        input.put("toolCallId", event.toolCallId());
        input.put("rewritten", event.rewritten());
        input.put("resultExternalized", event.resultExternalized());
        Map<String, Object> output = new LinkedHashMap<>(event.metadata() == null ? Map.of() : event.metadata());
        output.put("error", event.error());

        TraceError traceError = null;
        if (event.error()) {
            traceError = new TraceError(
                    Instant.ofEpochMilli(event.finishedAtMillis()),
                    "TOOL_FAILURE",
                    event.errorCategory() == null ? "TOOL" : event.errorCategory(),
                    "SKIP",
                    "tool failed",
                    null,
                    event.metadata());
        }
        TraceToolCall span = new TraceToolCall(
                Instant.ofEpochMilli(event.startedAtMillis()),
                event.toolName(),
                input,
                output,
                null,
                "tool",
                "ALLOW",
                Math.max(0L, event.finishedAtMillis() - event.startedAtMillis()),
                traceError);
        turnTrace.recordToolCall(span);
    }
}