package com.pixflow.harness.eval.store;

import com.pixflow.harness.eval.api.TraceReplay;
import com.pixflow.harness.eval.model.ReplayedTurn;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import com.pixflow.harness.eval.support.TracePayloadCodec;
import java.util.NoSuchElementException;

public final class DefaultTraceReplay implements TraceReplay {
    private final AgentTraceRepository repository;

    private final TracePayloadCodec codec;

    public DefaultTraceReplay(AgentTraceRepository repository, TracePayloadCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    public ReplayedTurn replay(String conversationId, int turnNo) {
        AgentTraceEntity entity = repository.findByTurn(conversationId, turnNo)
                .orElseThrow(() -> new NoSuchElementException("trace not found: " + conversationId + "#" + turnNo));
        TurnTraceRecord record = codec.toRecord(entity);
        TracePayloadCodec.RehydratedColumn input = codec.decodeColumn(entity.inputJson());
        TracePayloadCodec.RehydratedColumn tools = codec.decodeColumn(entity.toolCallsJson());
        TracePayloadCodec.RehydratedColumn recall = codec.decodeColumn(entity.recallJson());
        TracePayloadCodec.RehydratedColumn prune = codec.decodeColumn(entity.pruneLogJson());
        TracePayloadCodec.RehydratedColumn error = codec.decodeColumn(entity.errorJson());
        return new ReplayedTurn(
                record,
                input.json(),
                tools.json(),
                recall.json(),
                prune.json(),
                error.json(),
                input.missingExternal()
                        || tools.missingExternal()
                        || recall.missingExternal()
                        || prune.missingExternal()
                        || error.missingExternal());
    }
}
