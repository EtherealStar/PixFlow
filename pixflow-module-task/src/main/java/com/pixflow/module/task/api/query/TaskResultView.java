package com.pixflow.module.task.api.query;

import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.harness.state.model.UnitKind;
import java.net.URL;
import java.time.Instant;

public record TaskResultView(
        String resultId,
        String taskId,
        String conversationId,
        ResultStatus status,
        UnitKind kind,
        String imageId,
        String skuId,
        String groupKey,
        String viewId,
        String branchId,
        String filename,
        String displayName,
        Long size,
        URL url,
        Instant createdAt,
        Instant finishedAt,
        String errorMsg) {
}
