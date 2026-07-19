package com.pixflow.module.task.internal.query;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.api.download.PublishedTaskResultQuery;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.PublicationStatus;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;

public final class PublishedTaskResultQueryImpl implements PublishedTaskResultQuery {
  private final ProcessResultMapper results;

  public PublishedTaskResultQueryImpl(ProcessResultMapper results) {
    this.results = results;
  }

  @Override
  public PublishedTaskResult require(long resultId) {
    var result = results.selectById(resultId);
    if (result == null
        || result.getDeletedAt() != null
        || result.getStatus() != ResultStatus.SUCCESS
        || result.getPublicationStatus() != PublicationStatus.PUBLISHED
        || result.getPublishedReferenceKey() == null) {
      throw new PixFlowException(
          TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "task result is not ready for bundle");
    }
    return new PublishedTaskResult(
        result.getId(), result.getPublishedReferenceKey(), result.getDisplayName());
  }
}
