package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.dag.exec.UnitExecutor;
import com.pixflow.module.dag.exec.UnitInput;
import com.pixflow.module.dag.exec.UnitOutcome;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.ValidatedDag;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class ProcessWorker {
    private final ObjectMapper objectMapper;
    private final BranchExpander branchExpander;
    private final UnitExecutor unitExecutor;
    private final TaskAssetReader assetReader;
    private final ProcessResultMapper resultMapper;
    private final ProcessResultMemberMapper memberMapper;
    private final ProgressAggregator progressAggregator;
    private final FailureIsolator failureIsolator;
    private final CancellationService cancellationService;
    private final TaskMetrics metrics;
    private final Clock clock;

    public ProcessWorker(ObjectMapper objectMapper,
                         BranchExpander branchExpander,
                         UnitExecutor unitExecutor,
                         TaskAssetReader assetReader,
                         ProcessResultMapper resultMapper,
                         ProcessResultMemberMapper memberMapper,
                         ProgressAggregator progressAggregator,
                         FailureIsolator failureIsolator,
                         CancellationService cancellationService,
                         TaskMetrics metrics,
                         Clock clock) {
        this.objectMapper = objectMapper;
        this.branchExpander = branchExpander;
        this.unitExecutor = unitExecutor;
        this.assetReader = assetReader;
        this.resultMapper = resultMapper;
        this.memberMapper = memberMapper;
        this.progressAggregator = progressAggregator;
        this.failureIsolator = failureIsolator;
        this.cancellationService = cancellationService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<WorkUnit> plan(String taskId, long packageId, String payload) {
        try {
            ValidatedDag dag = objectMapper.readValue(payload, ValidatedDag.class);
            List<ImageDescriptor> images = assetReader.listImages(packageId);
            return branchExpander.expand(dag, images).stream()
                    .map(branch -> WorkUnit.branch(taskId, branch, inputImages(branch, images)))
                    .toList();
        } catch (Exception e) {
            throw new PixFlowException(TaskErrorCode.TASK_DAG_PAYLOAD_INVALID, "invalid DAG payload", e);
        }
    }

    public void execute(WorkUnit unit, UnitExecutionContext context) {
        if (skipIfCancelled(unit, context)) {
            return;
        }
        java.time.Instant started = clock.instant();
        try {
            UnitOutcome outcome = unitExecutor.execute(unit.executableBranch(), UnitInput.images(unit.imageDescriptors()));
            if (outcome.status() == UnitOutcome.Status.SUCCEEDED) {
                writeSuccess(unit, outcome, context);
                progressAggregator.success(unit.taskId(), context.totalUnits());
                metrics.recordWorker(unit.taskType(), "success", Duration.between(started, clock.instant()));
            } else {
                failureIsolator.handle(unit, new PixFlowException(outcome.error().code(), outcome.error().safeMessage()), context);
                metrics.recordWorker(unit.taskType(), "failed", Duration.between(started, clock.instant()));
            }
        } catch (Throwable t) {
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

    private void writeSuccess(WorkUnit unit, UnitOutcome outcome, UnitExecutionContext context) {
        ProcessResult result = failureIsolator.baseResult(unit, context);
        result.setStatus(ResultStatus.SUCCESS);
        result.setOutputMinioKey(outcome.outputObjectKey());
        result.setGeneratedCopy(outcome.generatedCopy());
        result.setFinishedAt(clock.instant());
        resultMapper.insert(result);
        for (UnitOutcome.MemberRef member : outcome.members()) {
            ProcessResultMember row = new ProcessResultMember();
            row.setResultId(result.getId());
            row.setTaskId(Long.parseLong(unit.taskId()));
            row.setImageId(member.imageId());
            row.setViewId(member.viewId());
            row.setSourcePath(member.sourceObjectKey());
            row.setCreatedAt(clock.instant());
            memberMapper.insert(row);
        }
    }

    private static List<ImageDescriptor> inputImages(ExecutableBranch branch, List<ImageDescriptor> all) {
        if (branch.kind() == com.pixflow.harness.state.model.UnitKind.GROUP) {
            return all.stream().filter(img -> branch.memberId().equals(img.groupKey())).toList();
        }
        return all.stream().filter(img -> branch.memberId().equals(img.imageId())).toList();
    }
}
