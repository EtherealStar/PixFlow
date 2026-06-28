package com.pixflow.harness.eval.store;

import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.harness.eval.model.TraceQueryCriteria;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import com.pixflow.harness.eval.support.TracePayloadCodec;
import java.util.Optional;

public final class DefaultTraceQuery implements TraceQuery {
    private final AgentTraceRepository repository;
    private final TracePayloadCodec codec;

    public DefaultTraceQuery(AgentTraceRepository repository, TracePayloadCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    public Optional<TurnTraceRecord> getTurn(String conversationId, int turnNo) {
        return repository.findByTurn(conversationId, turnNo).map(codec::toRecord);
    }

    @Override
    public PageResponse<TurnTraceRecord> listByConversation(String conversationId, Pagination page) {
        PageResponse<AgentTraceEntity> response = repository.listByConversation(conversationId, page);
        return PageResponse.of(response.records().stream().map(codec::toRecord).toList(), response.total(), response.page(), response.size());
    }

    @Override
    public PageResponse<TurnTraceRecord> query(TraceQueryCriteria criteria, Pagination page) {
        PageResponse<AgentTraceEntity> response = repository.query(criteria, page);
        return PageResponse.of(response.records().stream().map(codec::toRecord).toList(), response.total(), response.page(), response.size());
    }
}
