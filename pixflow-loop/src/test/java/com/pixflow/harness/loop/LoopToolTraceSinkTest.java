package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.loop.trace.LoopToolTraceSink;
import com.pixflow.harness.tools.result.ToolTraceSink.ToolTraceEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link LoopToolTraceSink} 把 {@link ToolTraceEvent} 转投到 {@code TurnTrace.recordToolCall}。
 *
 * <p>{@code rewritten=true} 必须反映到 {@code TraceToolCall.input("rewritten", true)}。
 */
class LoopToolTraceSinkTest {

    @Test
    void rewrittenFlagFlowsThroughIntoTraceToolCallInput() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        LoopToolTraceSink sink = new LoopToolTraceSink(turn);
        long started = Instant.now().toEpochMilli() - 10;
        long finished = Instant.now().toEpochMilli();
        sink.record(new ToolTraceEvent(
                "search",
                "tc1",
                started,
                finished,
                false,
                null,
                true,   // rewritten
                false,
                Map.of("preview", "preview")));
        turn.commit();
        List<InMemoryTraceRecorder.InMemoryTurnTrace> traces = rec.traces();
        assertThat(traces).hasSize(1);
        InMemoryTraceRecorder.InMemoryTurnTrace tr = traces.get(0);
        assertThat(tr.toolCalls()).hasSize(1);
        var tc = tr.toolCalls().get(0);
        assertThat(tc.name()).isEqualTo("search");
        assertThat(tc.input()).containsEntry("rewritten", true);
        assertThat(tc.latencyMs()).isEqualTo(finished - started);
    }

    @Test
    void errorFlagProducesTraceError() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        LoopToolTraceSink sink = new LoopToolTraceSink(turn);
        sink.record(new ToolTraceEvent(
                "failtool",
                "tc-fail",
                Instant.now().toEpochMilli() - 5,
                Instant.now().toEpochMilli(),
                true,
                "TOOL",
                false,
                false,
                Map.of("reason", "boom")));
        turn.commit();
        InMemoryTraceRecorder.InMemoryTurnTrace tr = rec.traces().get(0);
        assertThat(tr.toolCalls()).hasSize(1);
        var tc = tr.toolCalls().get(0);
        assertThat(tc.error()).isNotNull();
        assertThat(tc.error().category()).isEqualTo("TOOL");
    }
}