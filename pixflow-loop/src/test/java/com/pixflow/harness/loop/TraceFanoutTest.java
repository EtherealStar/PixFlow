package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.loop.trace.TraceFanout;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link TraceFanout} 把 prune / hook / retry 数据转投到 TurnTrace。
 */
class TraceFanoutTest {

    @Test
    void pruneEntriesFlowIntoRecordPrune() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        TraceFanout fan = new TraceFanout(turn);

        fan.fanoutPrune(List.of(
                new TracePruneEntry(Instant.now(), "AUTO", 100, 50, "summarized", Map.of("trigger", "AUTO")),
                new TracePruneEntry(Instant.now(), "MICRO", 80, 60, "microcompact", Map.of())));

        turn.commit();
        InMemoryTraceRecorder.InMemoryTurnTrace tr = rec.traces().get(0);
        assertThat(tr.prunes()).hasSize(2);
        assertThat(tr.prunes().get(0).phase()).isEqualTo("AUTO");
        assertThat(tr.prunes().get(1).phase()).isEqualTo("MICRO");
    }

    @Test
    void fanoutPruneIgnoresNullAndEmpty() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        TraceFanout fan = new TraceFanout(turn);
        fan.fanoutPrune(null);
        fan.fanoutPrune(List.of());
        turn.commit();
        assertThat(rec.traces().get(0).prunes()).isEmpty();
    }

    @Test
    void fanoutHookSpanRecordsBlockedReasonAsTraceError() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        TraceFanout fan = new TraceFanout(turn);
        HookResult result = HookResult.block("blocked by policy");
        fan.fanoutHookSpan(HookEvent.PRE_TOOL_USE, result, "search", "tc1", 5L);
        turn.commit();
        InMemoryTraceRecorder.InMemoryTurnTrace tr = rec.traces().get(0);
        assertThat(tr.toolCalls()).hasSize(1);
        var tc = tr.toolCalls().get(0);
        assertThat(tc.name()).isEqualTo("loop.hook.pre_tool_use");
        assertThat(tc.error()).isNotNull();
        assertThat(tc.error().code()).isEqualTo("HOOK_BLOCKED");
        assertThat(tc.error().category()).isEqualTo("VALIDATION");
        assertThat(tc.error().recovery()).isEqualTo("SKIP");
        assertThat(tc.error().message()).isEqualTo("blocked by policy");
    }

    @Test
    void fanoutRetryRecordsNameAsReason() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        TraceFanout fan = new TraceFanout(turn);
        fan.fanoutRetry(TransitionReason.RATE_LIMIT_RETRY, 0L);
        turn.commit();
        InMemoryTraceRecorder.InMemoryTurnTrace tr = rec.traces().get(0);
        assertThat(tr.toolCalls()).hasSize(1);
        assertThat(tr.toolCalls().get(0).name()).isEqualTo("loop.retry");
    }

    @Test
    void recallSnapshotFlowsOnceAndDoesNotExposeModelText() {
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        TurnTrace turn = rec.begin("c", 1, "t", RuntimeScope.MAIN);
        TraceFanout fan = new TraceFanout(turn);
        Map<String, Object> snapshot = Map.of(
                "degraded", true,
                "token_budget", 4000,
                "used_tokens", 120,
                "sections", List.of(Map.of(
                        "name", "analysis_insights",
                        "candidate_count", 4,
                        "selected_count", 1,
                        "degraded_reasons", List.of("vector_unavailable"))));

        fan.fanoutRecall(snapshot);
        fan.fanoutRecall(snapshot);

        turn.commit();
        List<TraceRecall> recalls = rec.traces().get(0).recalls();
        assertThat(recalls).hasSize(1);
        assertThat(recalls.get(0).source()).isEqualTo("memory");
        assertThat(recalls.get(0).metadata()).containsEntry("degraded", true);
        assertThat(recalls.toString()).doesNotContain("selected_text");
    }

}
