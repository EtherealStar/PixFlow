package com.pixflow.harness.eval.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.config.EvalProperties;
import com.pixflow.harness.eval.error.EvalErrorRecorder;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceQueryCriteria;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.eval.model.TurnStatus;
import com.pixflow.harness.eval.store.AgentTraceEntity;
import com.pixflow.harness.eval.store.DefaultTraceQuery;
import com.pixflow.harness.eval.store.DefaultTraceReplay;
import com.pixflow.harness.eval.store.InMemoryAgentTraceRepository;
import com.pixflow.harness.eval.support.TraceExternalPayloadStorage;
import com.pixflow.harness.eval.support.TracePayloadCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceRecorderTest {

    @Test
    void recordsOpenAndCommittedTurnWithoutLeakingSecrets() {
        Fixture fixture = new Fixture(10_000, 10);
        TraceRecorder recorder = new DefaultTraceRecorder(fixture.buffer);

        TurnTrace trace = recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        fixture.buffer.flushNow();
        assertThat(fixture.repository.findByTurn("conv-1", 1)).get().extracting(AgentTraceEntity::turnStatus).isEqualTo(TurnStatus.OPEN);

        trace.recordInput(new TraceInput(null, "model", "api_key=sk-1234567890abcdef email=a@example.com", null, null, Map.of("username", "张三")));
        trace.recordToolCall(new TraceToolCall(null, "search", Map.of("authorization", "Bearer raw-token"), "phone=13800138000", null, "READ", "ALLOW", 12, null));
        trace.recordPrune(new TracePruneEntry(null, "cheap", 1000, 800, "D:\\study\\PixFlow\\secret.txt", null));
        trace.commit();
        fixture.buffer.flushNow();

        AgentTraceEntity row = fixture.repository.findByTurn("conv-1", 1).orElseThrow();
        assertThat(row.turnStatus()).isEqualTo(TurnStatus.COMMITTED);
        assertThat(row.inputJson()).doesNotContain("sk-1234567890abcdef", "a@example.com", "张三");
        assertThat(row.toolCallsJson()).doesNotContain("raw-token", "13800138000");
        assertThat(row.pruneLogJson()).doesNotContain("D:\\study\\PixFlow\\secret.txt");
    }

    @Test
    void doesNotLetLateOpenOverwriteCommittedState() {
        Fixture fixture = new Fixture(10_000, 10);
        TraceRecorder recorder = new DefaultTraceRecorder(fixture.buffer);
        TurnTrace first = recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        first.commit();
        fixture.buffer.flushNow();

        recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        fixture.buffer.flushNow();

        assertThat(fixture.repository.findByTurn("conv-1", 1)).get().extracting(AgentTraceEntity::turnStatus).isEqualTo(TurnStatus.COMMITTED);
    }

    @Test
    void dropsWhenBufferIsFull() {
        Fixture fixture = new Fixture(10_000, 1);
        TraceRecorder recorder = new DefaultTraceRecorder(fixture.buffer);

        recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        recorder.begin("conv-1", 2, "trace-2", RuntimeScope.MAIN);

        assertThat(fixture.buffer.droppedCount()).isEqualTo(1);
    }

    @Test
    void externalizesLargePayloadAfterSanitizingAndReplaysIt() {
        Fixture fixture = new Fixture(100, 10);
        TraceRecorder recorder = new DefaultTraceRecorder(fixture.buffer);

        TurnTrace trace = recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        trace.recordInput(new TraceInput(null, "model", "sk-1234567890abcdef " + "x".repeat(300), null, null, Map.of()));
        trace.commit();
        fixture.buffer.flushNow();

        AgentTraceEntity row = fixture.repository.findByTurn("conv-1", 1).orElseThrow();
        assertThat(row.inputJson()).contains("__external");
        assertThat(fixture.external.lastPayload).doesNotContain("sk-1234567890abcdef");

        DefaultTraceReplay replay = new DefaultTraceReplay(fixture.repository, fixture.codec);
        assertThat(replay.replay("conv-1", 1).inputJson()).contains("***").doesNotContain("sk-1234567890abcdef");
    }

    @Test
    void queryAndErrorRecorderAttachTurnErrors() {
        Fixture fixture = new Fixture(10_000, 10);
        TraceRecorder recorder = new DefaultTraceRecorder(fixture.buffer);
        TurnTrace trace = recorder.begin("conv-1", 1, "trace-1", RuntimeScope.MAIN);
        EvalErrorRecorder errorRecorder = new EvalErrorRecorder(fixture.meterRegistry);

        try (CurrentTurnTraceHolder.Scope ignored = CurrentTurnTraceHolder.bind(trace)) {
            errorRecorder.record(new PixFlowException(CommonErrorCode.TOOL_FAILURE, "tool failed", null, Map.of("token", "raw")));
        }
        trace.abort(new PixFlowException(CommonErrorCode.INTERNAL_ERROR, "abort"));
        fixture.buffer.flushNow();

        AgentTraceEntity row = fixture.repository.findByTurn("conv-1", 1).orElseThrow();
        assertThat(row.turnStatus()).isEqualTo(TurnStatus.ABORTED);
        assertThat(row.errorJson()).contains("TOOL_FAILURE").doesNotContain("raw");

        DefaultTraceQuery query = new DefaultTraceQuery(fixture.repository, fixture.codec);
        assertThat(query.query(new TraceQueryCriteria(null, null, "conv-1", null, TurnStatus.ABORTED, null, null, true, null), Pagination.of(1L, 20L)).total())
                .isEqualTo(1);
    }

    private static final class Fixture {
        final EvalProperties properties = new EvalProperties();
        final InMemoryAgentTraceRepository repository = new InMemoryAgentTraceRepository();
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final FakeExternalStorage external = new FakeExternalStorage();
        final TracePayloadCodec codec;
        final TraceIngestBuffer buffer;

        Fixture(int threshold, int capacity) {
            properties.setColumnExternalizeThreshold(threshold);
            properties.getBuffer().setCapacity(capacity);
            properties.getBuffer().setFlushInterval(Duration.ofSeconds(60));
            codec = new TracePayloadCodec(new ObjectMapper().findAndRegisterModules(), properties, external);
            buffer = new TraceIngestBuffer(properties, codec, repository, meterRegistry);
        }
    }

    private static final class FakeExternalStorage implements TraceExternalPayloadStorage {
        String lastPayload;

        @Override
        public com.pixflow.harness.eval.model.TraceExternalPayloadRef put(String payload) {
            lastPayload = payload;
            return new com.pixflow.harness.eval.model.TraceExternalPayloadRef("key-1", payload.length(), "etag", "sha", payload.substring(0, Math.min(payload.length(), 1000)), false);
        }

        @Override
        public String get(com.pixflow.harness.eval.model.TraceExternalPayloadRef ref) {
            return lastPayload;
        }

        @Override
        public void delete(com.pixflow.harness.eval.model.TraceExternalPayloadRef ref) {
            lastPayload = null;
        }
    }
}
