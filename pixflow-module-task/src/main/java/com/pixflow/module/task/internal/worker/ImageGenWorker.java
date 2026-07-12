package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.exec.GeneratedArtifact;
import com.pixflow.module.imagegen.exec.GenerativeUnitSpec;
import com.pixflow.module.imagegen.exec.ImageGenExecutor;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class ImageGenWorker {
    private final ObjectMapper objectMapper;
    private final ImageGenExecutor executor;
    private final TaskAssetReader assetReader;
    private final FailureIsolator failureIsolator;
    private final CancellationService cancellationService;
    private final TaskMetrics metrics;
    private final Clock clock;

    public ImageGenWorker(ObjectMapper objectMapper,
                          ImageGenExecutor executor,
                          TaskAssetReader assetReader,
                          FailureIsolator failureIsolator,
                          CancellationService cancellationService,
                          TaskMetrics metrics,
                          Clock clock) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.assetReader = assetReader;
        this.failureIsolator = failureIsolator;
        this.cancellationService = cancellationService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<WorkUnit> plan(String taskId, long packageId, long runEpoch, String payload) {
        try {
            ImagegenPlan plan = objectMapper.readValue(payload, ImagegenPlan.class);
            return plan.sourceImageIds().stream()
                    .map(imageId -> {
                        TaskAssetReader.GenerativeSource source = assetReader.sourceImage(packageId, imageId);
                        UnitKey unitKey = UnitKey.generative(taskId, source.sourceImageId());
                        GenerativeUnitSpec spec = new GenerativeUnitSpec(taskId, UnitKeyCodec.sha256(unitKey), runEpoch,
                                source.skuId(),
                                source.sourceImageId(), source.location(), plan.prompt(), plan.params(), "png");
                        return WorkUnit.generative(taskId, spec);
                    })
                    .toList();
        } catch (Exception e) {
            throw new PixFlowException(TaskErrorCode.TASK_IMAGEGEN_PAYLOAD_INVALID,
                    "invalid imagegen payload", e);
        }
    }

    public WorkUnitCompletion execute(WorkUnit unit, ExecutionRun run) {
        java.time.Instant started = clock.instant();
        if (cancellationService.isCancelRequested(unit.taskId())) {
            metrics.recordWorker(unit.taskType(), "skipped", Duration.ZERO);
            return new WorkUnitCompletion.Skipped(unit, run.epoch(), started, clock.instant());
        }
        try {
            GeneratedArtifact artifact = executor.redraw(unit.generativeSpec());
            metrics.recordWorker(unit.taskType(), "success", Duration.between(started, clock.instant()));
            return new WorkUnitCompletion.Succeeded(unit, run.epoch(), started, clock.instant(),
                    artifact.output().key(), null, artifact.output().size(), List.of());
        } catch (Exception t) {
            metrics.recordWorker(unit.taskType(), "failed", Duration.between(started, clock.instant()));
            return failureIsolator.isolate(unit, t, run.epoch(), started);
        }
    }
}
