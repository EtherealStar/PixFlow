package com.pixflow.module.rubrics.run;

import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;

public record EvaluationItem(long runId, long itemId, ProcessResult result, ProcessTask task) {
}
