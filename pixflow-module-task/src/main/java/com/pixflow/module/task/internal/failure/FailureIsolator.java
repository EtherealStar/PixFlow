package com.pixflow.module.task.internal.failure;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import com.pixflow.module.task.internal.worker.UnitExecutionContext;
import java.time.Clock;

public class FailureIsolator {
    private final ProcessResultMapper resultMapper;
    private final ProgressAggregator progressAggregator;
    private final ErrorNormalizer normalizer;
    private final TaskMetrics metrics;
    private final Clock clock;

    public FailureIsolator(ProcessResultMapper resultMapper,
                           ProgressAggregator progressAggregator,
                           ErrorNormalizer normalizer,
                           TaskMetrics metrics,
                           Clock clock) {
        this.resultMapper = resultMapper;
        this.progressAggregator = progressAggregator;
        this.normalizer = normalizer;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void handle(WorkUnit unit, Throwable throwable, UnitExecutionContext context) {
        PixFlowException normalized = normalizer.normalize(throwable);
        ProcessResult result = baseResult(unit, context);
        result.setStatus(ResultStatus.FAILED);
        result.setErrorCode(normalized.code().code());
        result.setErrorMsg(Sanitizer.sanitizeMessage(normalized.getMessage()));
        result.setFinishedAt(clock.instant());
        resultMapper.insert(result);
        progressAggregator.failed(unit.taskId(), context.totalUnits());
        metrics.recordFailure(normalized.code() == null ? TaskErrorCode.TASK_RESULT_WRITE_FAILED : normalized.code());
    }

    public ProcessResult baseResult(WorkUnit unit, UnitExecutionContext context) {
        ProcessResult result = new ProcessResult();
        result.setTaskId(Long.parseLong(unit.taskId()));
        result.setKind(unit.kind());
        result.setBranchId(unit.branchId());
        result.setAttemptCount(context.attemptCount());
        result.setWorkerRunId(context.workerRunId());
        result.setStartedAt(clock.instant());
        if (unit.kind() == com.pixflow.module.task.domain.model.UnitKind.GROUP) {
            result.setGroupKey(unit.memberId());
        } else {
            result.setImageId(unit.memberId());
        }
        if (!unit.imageDescriptors().isEmpty()) {
            var first = unit.imageDescriptors().get(0);
            result.setSkuId(first.skuId());
            result.setViewId(first.viewId());
            result.setSourcePath(first.objectKey());
        } else if (unit.generativeSpec() != null) {
            result.setSkuId(unit.generativeSpec().skuId());
            result.setSourcePath(unit.generativeSpec().sourceLocation().key());
        }
        return result;
    }
}
