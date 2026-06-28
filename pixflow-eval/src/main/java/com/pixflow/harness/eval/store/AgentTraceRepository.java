package com.pixflow.harness.eval.store;

import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.model.TraceQueryCriteria;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentTraceRepository {
    void upsertBatch(List<AgentTraceEntity> entities);

    Optional<AgentTraceEntity> findByTurn(String conversationId, int turnNo);

    PageResponse<AgentTraceEntity> listByConversation(String conversationId, Pagination page);

    PageResponse<AgentTraceEntity> query(TraceQueryCriteria criteria, Pagination page);

    List<AgentTraceEntity> findExpired(Instant cutoff, int limit);

    void deleteByIds(List<Long> ids);
}
