package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.dag.DagFacade;
import com.pixflow.module.dag.exec.UnitExecutor;
import com.pixflow.module.dag.exec.UnitInput;
import com.pixflow.module.dag.exec.UnitOutcome;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.infra.storage.StorageKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProcessWorker {
    private final ObjectMapper objectMapper;
    private final DagJsonReader dagJsonReader;
    private final DagFacade dagFacade;
    private final BranchExpander branchExpander;
    private final UnitExecutor unitExecutor;
    private final FailureIsolator failureIsolator;
    private final CancellationService cancellationService;
    private final TaskMetrics metrics;
    private final Clock clock;

    public ProcessWorker(ObjectMapper objectMapper,
                         DagJsonReader dagJsonReader,
                         DagFacade dagFacade,
                         BranchExpander branchExpander,
                         UnitExecutor unitExecutor,
                         FailureIsolator failureIsolator,
                         CancellationService cancellationService,
                         TaskMetrics metrics,
                         Clock clock) {
        this.objectMapper = objectMapper;
        this.dagJsonReader = dagJsonReader;
        this.dagFacade = dagFacade;
        this.branchExpander = branchExpander;
        this.unitExecutor = unitExecutor;
        this.failureIsolator = failureIsolator;
        this.cancellationService = cancellationService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<WorkUnit> plan(String taskId, long packageId, String payload, String selectionJson) {
        try {
            var root = objectMapper.readTree(payload);
            var versionNode = root.get("schemaVersion");
            if (versionNode == null || !versionNode.isTextual()) {
                throw new IllegalArgumentException("canonical DAG 缺 schemaVersion");
            }
            // 持久化只保存 canonical JSON；worker 每次从该事实重建类型化计划，避免执行原始参数 map。
            var canonical = dagFacade.validateToCanonical(
                    dagJsonReader.readTree(root), new DagSchemaVersion(versionNode.asText()));
            var plan = dagFacade.compile(canonical);
            WorkUnitSelection selection = objectMapper.readValue(selectionJson, WorkUnitSelection.class);
            List<ImageDescriptor> frozenImages = selection.items().stream()
                    .flatMap(item -> item.images().stream()).distinct().toList();
            var branches = branchExpander.expand(plan, frozenImages);
            Map<UnitIdentity, WorkUnitSelection.Item> selectedByIdentity = uniqueSelection(selection.items());
            Map<UnitIdentity, ExecutableBranch> branchesByIdentity = uniqueBranches(branches);
            // selection 是创建时冻结的执行事实；除了 identity 集合，输入图片快照也必须逐项一致。
            if (!branchesByIdentity.keySet().equals(selectedByIdentity.keySet())) {
                throw new IllegalStateException("重建单元 identity 与冻结 selection 不一致");
            }
            return branches.stream()
                    .map(branch -> {
                        var selected = selectedByIdentity.get(UnitIdentity.of(branch));
                        if (!selected.images().equals(inputImages(branch, frozenImages))) {
                            throw new IllegalStateException(
                                    "重建单元图片快照与冻结 selection 不一致: " + branch.branchId());
                        }
                        return WorkUnit.branch(taskId, branch, selected.images());
                    })
                    .toList();
        } catch (Exception e) {
            throw new PixFlowException(TaskErrorCode.TASK_DAG_PAYLOAD_INVALID, "invalid DAG payload", e);
        }
    }

    public WorkUnitCompletion execute(WorkUnit unit, ExecutionRun run) {
        java.time.Instant started = clock.instant();
        if (cancellationService.isCancelRequested(unit.taskId())) {
            metrics.recordWorker(unit.taskType(), "skipped", Duration.ZERO);
            return new WorkUnitCompletion.Skipped(unit, run.epoch(), started, clock.instant());
        }
        try {
            // 对象目标由 task ownership 边界生成；executor 不得自行拼接缺少 task/epoch 的路径。
            String outputKey = StorageKeys.resultUnit(unit.taskId(), UnitKeyCodec.sha256(unit.unitKey()),
                    run.epoch(), outputExtension(unit.executableBranch())).key();
            UnitOutcome outcome = unitExecutor.execute(unit.executableBranch(),
                    UnitInput.images(unit.imageDescriptors(), outputKey, unit.unitKey(), run.epoch()));
            if (outcome.status() == UnitOutcome.Status.SUCCEEDED) {
                metrics.recordWorker(unit.taskType(), "success", Duration.between(started, clock.instant()));
                return new WorkUnitCompletion.Succeeded(unit, run.epoch(), started, clock.instant(),
                        outcome.outputObjectKey(), outcome.generatedCopy(), null, outcome.members());
            }
            metrics.recordWorker(unit.taskType(), "failed", Duration.between(started, clock.instant()));
            return failureIsolator.isolate(unit,
                    new PixFlowException(outcome.error().code(), outcome.error().safeMessage()), run.epoch(), started);
        } catch (Exception t) {
            metrics.recordWorker(unit.taskType(), "failed", Duration.between(started, clock.instant()));
            return failureIsolator.isolate(unit, t, run.epoch(), started);
        }
    }

    private static List<ImageDescriptor> inputImages(ExecutableBranch branch, List<ImageDescriptor> all) {
        if (branch.kind() == com.pixflow.harness.state.model.UnitKind.GROUP) {
            return all.stream().filter(img -> branch.memberId().equals(img.groupKey())).toList();
        }
        return all.stream().filter(img -> branch.memberId().equals(img.imageId())).toList();
    }

    private static String outputExtension(ExecutableBranch branch) {
        return branch.encode().format().toLowerCase(java.util.Locale.ROOT).replace("jpeg", "jpg");
    }

    private static Map<UnitIdentity, WorkUnitSelection.Item> uniqueSelection(
            List<WorkUnitSelection.Item> items) {
        Map<UnitIdentity, WorkUnitSelection.Item> unique = new LinkedHashMap<>();
        for (WorkUnitSelection.Item item : items) {
            if (unique.put(UnitIdentity.of(item), item) != null) {
                throw new IllegalStateException("冻结 selection 包含重复单元 identity: " + item.branchId());
            }
        }
        return unique;
    }

    private static Map<UnitIdentity, ExecutableBranch> uniqueBranches(List<ExecutableBranch> branches) {
        Map<UnitIdentity, ExecutableBranch> unique = new LinkedHashMap<>();
        for (ExecutableBranch branch : branches) {
            if (unique.put(UnitIdentity.of(branch), branch) != null) {
                throw new IllegalStateException("重建计划包含重复单元 identity: " + branch.branchId());
            }
        }
        return unique;
    }

    private record UnitIdentity(com.pixflow.harness.state.model.UnitKind kind,
                                String memberId, String branchId) {
        private static UnitIdentity of(WorkUnitSelection.Item item) {
            return new UnitIdentity(item.kind(), item.memberId(), item.branchId());
        }

        private static UnitIdentity of(ExecutableBranch branch) {
            return new UnitIdentity(branch.kind(), branch.memberId(), branch.branchId());
        }
    }
}
