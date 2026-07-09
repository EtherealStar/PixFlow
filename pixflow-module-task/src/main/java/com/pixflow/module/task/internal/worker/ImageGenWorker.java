package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.exec.GeneratedArtifact;
import com.pixflow.module.imagegen.exec.GenerativeUnitSpec;
import com.pixflow.module.imagegen.exec.ImageGenExecutor;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class ImageGenWorker {
    private final ObjectMapper objectMapper;
    private final ImageGenExecutor executor;
    private final TaskAssetReader assetReader;
    private final ProcessResultMapper resultMapper;
    private final ProgressAggregator progressAggregator;
    private final FailureIsolator failureIsolator;
    private final CancellationService cancellationService;
    private final TaskMetrics metrics;
    private final Clock clock;

    public ImageGenWorker(ObjectMapper objectMapper,
                          ImageGenExecutor executor,
                          TaskAssetReader assetReader,
                          ProcessResultMapper resultMapper,
                          ProgressAggregator progressAggregator,
                          FailureIsolator failureIsolator,
                          CancellationService cancellationService,
                          TaskMetrics metrics,
                          Clock clock) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.assetReader = assetReader;
        this.resultMapper = resultMapper;
        this.progressAggregator = progressAggregator;
        this.failureIsolator = failureIsolator;
        this.cancellationService = cancellationService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<WorkUnit> plan(String taskId, long packageId, String payload) {
        try {
            ImagegenPlan plan = objectMapper.readValue(payload, ImagegenPlan.class);
            return plan.sourceImageIds().stream()
                    .map(imageId -> {
                        TaskAssetReader.GenerativeSource source = assetReader.sourceImage(packageId, imageId);
                        GenerativeUnitSpec spec = new GenerativeUnitSpec(taskId, source.skuId(),
                                source.sourceImageId(), source.location(), plan.prompt(), plan.params(), "png");
                        return WorkUnit.generative(taskId, spec);
                    })
                    .toList();
        } catch (Exception e) {
            throw new PixFlowException(TaskErrorCode.TASK_IMAGEGEN_PAYLOAD_INVALID,
                    "invalid imagegen payload", e);
        }
    }

    public void execute(WorkUnit unit, UnitExecutionContext context) {
        if (skipIfCancelled(unit, context)) {
            return;
        }
        java.time.Instant started = clock.instant();
        try {
            GeneratedArtifact artifact = executor.redraw(unit.generativeSpec());
            ProcessResult result = failureIsolator.baseResult(unit, context);
            result.setStatus(ResultStatus.SUCCESS);
            result.setOutputMinioKey(artifact.output().key());
            result.setBytesOut(artifact.output().size());
            result.setFinishedAt(clock.instant());
            resultMapper.insert(result);
            progressAggregator.success(unit.taskId(), context.totalUnits());
            metrics.recordWorker(unit.taskType(), "success", Duration.between(started, clock.instant()));
        } catch (Exception t) {
            failureIsolator.handle(unit, t, context);
            metrics.recordWorker(unit.taskType(), "failed", Duration.between(started, clock.instant()));
        }
    }

    private boolean skipIfCancelled(WorkUnit unit, UnitExecutionContext context) {
        if (!cancellationService.isCancelRequested(unit.taskId())) {
            return false;
        }
        ProcessResult result = failureIsolator.baseResult(unit, context);
        result.setStatus(ResultStatus.SKIPPED);
        result.setFinishedAt(clock.instant());
        resultMapper.insert(result);
        progressAggregator.skipped(unit.taskId(), context.totalUnits());
        metrics.recordWorker(unit.taskType(), "skipped", Duration.ZERO);
        return true;
    }
}
