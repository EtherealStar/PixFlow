package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.imagegen.exec.GeneratedArtifact;
import com.pixflow.module.imagegen.exec.GenerativeUnitSpec;
import com.pixflow.module.imagegen.exec.ImageGenExecutor;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class ImageGenWorker {
    private final ObjectMapper objectMapper;

    private final ImageGenExecutor executor;

    private final FailureIsolator failureIsolator;

    private final CancellationService cancellationService;

    private final TaskMetrics metrics;

    private final Clock clock;

    public ImageGenWorker(ObjectMapper objectMapper,
                          ImageGenExecutor executor,
                          FailureIsolator failureIsolator,
                          CancellationService cancellationService,
                          TaskMetrics metrics,
                          Clock clock) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.failureIsolator = failureIsolator;
        this.cancellationService = cancellationService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<WorkUnit> plan(String taskId, long runEpoch, String payload, String selectionJson) {
        try {
            ImagegenPlan plan = objectMapper.readValue(payload, ImagegenPlan.class);
            WorkUnitSelection selection = objectMapper.readValue(selectionJson, WorkUnitSelection.class);
            ImageAssetReferenceKey sourceReference = plan.sourceReference();
            String plannedSourceId = Long.toString(sourceReference.imageId());
            return selection.items().stream()
                    .map(item -> {
                        if (item.kind() != UnitKind.GENERATIVE || !"GENERATIVE".equals(item.branchId())
                                || !plannedSourceId.equals(item.memberId()) || item.images().size() != 1) {
                            throw new IllegalStateException("冻结的生成式 selection 与 Imagegen Plan 不一致");
                        }
                        var source = item.images().getFirst();
                        if (!item.memberId().equals(source.imageId()) || source.skuId() == null
                                || source.skuId().isBlank()) {
                            throw new IllegalStateException("冻结的生成式源图快照不完整");
                        }
                        UnitKey unitKey = UnitKey.generative(taskId, source.imageId());
                        GenerativeUnitSpec spec = new GenerativeUnitSpec(taskId, UnitKeyCodec.sha256(unitKey), runEpoch,
                                source.skuId(), source.imageId(),
                                ObjectLocation.of(BucketType.PACKAGES, source.objectKey()),
                                plan.prompt(), plan.params(), "png");
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
