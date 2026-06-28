package com.pixflow.harness.eval.api;

import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.model.TraceQueryCriteria;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import java.util.Optional;

public interface TraceQuery {
    Optional<TurnTraceRecord> getTurn(String conversationId, int turnNo);

    PageResponse<TurnTraceRecord> listByConversation(String conversationId, Pagination page);

    PageResponse<TurnTraceRecord> query(TraceQueryCriteria criteria, Pagination page);
}
