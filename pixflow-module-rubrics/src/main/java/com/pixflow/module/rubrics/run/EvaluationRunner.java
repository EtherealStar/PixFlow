package com.pixflow.module.rubrics.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.rubrics.feedback.MemoryFeedbackTrigger;
import com.pixflow.module.rubrics.feedback.ScoreFeedbackWriter;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EvaluationRunner {
    private final TemplateRegistry templateRegistry;
    private final ProcessResultMapper resultMapper;
    private final ProcessTaskMapper taskMapper;
    private final RubricsRunMapper runMapper;
    private final RubricsRunItemMapper runItemMapper;
    private final ItemEvaluator itemEvaluator;
    private final ScoreFeedbackWriter scoreFeedbackWriter;
    private final MemoryFeedbackTrigger memoryFeedbackTrigger;
    private final Clock clock;

    public EvaluationRunner(
            TemplateRegistry templateRegistry,
            ProcessResultMapper resultMapper,
            ProcessTaskMapper taskMapper,
            RubricsRunMapper runMapper,
            RubricsRunItemMapper runItemMapper,
            ItemEvaluator itemEvaluator,
            ScoreFeedbackWriter scoreFeedbackWriter,
            MemoryFeedbackTrigger memoryFeedbackTrigger,
            Clock clock) {
        this.templateRegistry = Objects.requireNonNull(templateRegistry, "templateRegistry");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
        this.runItemMapper = Objects.requireNonNull(runItemMapper, "runItemMapper");
        this.itemEvaluator = Objects.requireNonNull(itemEvaluator, "itemEvaluator");
        this.scoreFeedbackWriter = Objects.requireNonNull(scoreFeedbackWriter, "scoreFeedbackWriter");
        this.memoryFeedbackTrigger = Objects.requireNonNull(memoryFeedbackTrigger, "memoryFeedbackTrigger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RubricsRunView startManual(ManualRunRequest request) {
        RubricTemplate template = request.templateVersion() == null || request.templateVersion().isBlank()
                ? templateRegistry.requireLatest(request.templateId())
                : templateRegistry.require(request.templateId(), request.templateVersion());
        List<ProcessResult> results = resultMapper.selectBatchIds(request.resultIds()).stream()
                .filter(result -> result.getStatus() == ResultStatus.SUCCESS)
                .toList();
        return startRun(template, RunTriggerType.MANUAL, results);
    }

    public RubricsRunView startForTask(long taskId) {
        RubricTemplate template = templateRegistry.requireLatest("default");
        return startRun(template, RunTriggerType.TASK_COMPLETED,
                resultMapper.findByTaskIdAndStatus(taskId, ResultStatus.SUCCESS));
    }

    public RubricsRunView startDailyBatch() {
        RubricTemplate template = templateRegistry.requireLatest("default");
        Instant since = clock.instant().minus(java.time.Duration.ofHours(24));
        List<ProcessResult> results = resultMapper.selectList(new LambdaQueryWrapper<ProcessResult>()
                .eq(ProcessResult::getStatus, ResultStatus.SUCCESS)
                .ge(ProcessResult::getFinishedAt, since)
                .orderByAsc(ProcessResult::getId)
                .last("LIMIT 1000"));
        return startRun(template, RunTriggerType.DAILY_BATCH, results);
    }

    public void resumeRunning() {
        for (RubricsRunEntity run : runMapper.findByStatus(RunStatus.RUNNING)) {
            RubricTemplate template = templateRegistry.require(run.getTemplateId(), run.getTemplateVersion());
            evaluateRun(template, run);
        }
    }

    public RubricsRunView view(long runId) {
        return toView(runMapper.selectById(runId));
    }

    public List<RubricsRunView> list(int limit) {
        return runMapper.selectList(new LambdaQueryWrapper<RubricsRunEntity>()
                        .orderByDesc(RubricsRunEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(this::toView)
                .toList();
    }

    private RubricsRunView startRun(RubricTemplate template, RunTriggerType triggerType, List<ProcessResult> results) {
        Instant now = clock.instant();
        RubricsRunEntity run = new RubricsRunEntity();
        run.setTemplateId(template.id());
        run.setTemplateVersion(template.version());
        run.setTriggerType(triggerType);
        run.setStatus(RunStatus.RUNNING);
        run.setTotalCount(results.size());
        run.setSucceededCount(0);
        run.setIsolatedCount(0);
        run.setFailedCount(0);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        for (ProcessResult result : results) {
            RubricsRunItemEntity item = new RubricsRunItemEntity();
            item.setRunId(run.getId());
            item.setResultId(result.getId());
            item.setTaskId(result.getTaskId());
            item.setSkuId(result.getSkuId());
            item.setStatus(RunItemStatus.PENDING);
            item.setAttemptCount(0);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            runItemMapper.insert(item);
        }
        evaluateRun(template, run);
        return toView(runMapper.selectById(run.getId()));
    }

    private void evaluateRun(RubricTemplate template, RubricsRunEntity run) {
        List<RubricsRunItemEntity> items = runItemMapper.findRetryableByRunId(run.getId());
        Map<Long, ProcessResult> results = resultMapper.selectBatchIds(
                        items.stream().map(RubricsRunItemEntity::getResultId).toList())
                .stream()
                .collect(Collectors.toMap(ProcessResult::getId, Function.identity()));
        Map<Long, ProcessTask> tasks = results.values().stream()
                .map(ProcessResult::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .map(taskMapper::selectById)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ProcessTask::getId, Function.identity()));
        for (RubricsRunItemEntity item : items) {
            ProcessResult result = results.get(item.getResultId());
            if (result == null) {
                isolate(item, "process_result missing");
                continue;
            }
            try {
                runItemMapper.markRunning(item.getId(), RunItemStatus.RUNNING, clock.instant());
                ItemEvaluationResult evaluation = itemEvaluator.evaluate(template, result, tasks.get(result.getTaskId()));
                scoreFeedbackWriter.write(run.getId(), template, result, evaluation);
                runItemMapper.markFinished(item.getId(), RunItemStatus.SUCCEEDED, null, clock.instant());
            } catch (RuntimeException ex) {
                isolate(item, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }
        finishRun(run);
        memoryFeedbackTrigger.triggerForRun(run.getId());
    }

    private void isolate(RubricsRunItemEntity item, String errorMsg) {
        runItemMapper.markFinished(item.getId(), RunItemStatus.ISOLATED, truncate(errorMsg), clock.instant());
    }

    private void finishRun(RubricsRunEntity run) {
        List<RubricsRunItemEntity> items = runItemMapper.findByRunId(run.getId());
        int succeeded = count(items, RunItemStatus.SUCCEEDED);
        int isolated = count(items, RunItemStatus.ISOLATED);
        int failed = count(items, RunItemStatus.FAILED);
        RunStatus status = succeeded == items.size()
                ? RunStatus.SUCCEEDED
                : (succeeded > 0 ? RunStatus.PARTIAL : RunStatus.FAILED);
        runMapper.markFinished(run.getId(), status, succeeded, isolated, failed, null, clock.instant());
    }

    private RubricsRunView toView(RubricsRunEntity run) {
        return new RubricsRunView(
                run.getId(),
                run.getTemplateId(),
                run.getTemplateVersion(),
                run.getTriggerType(),
                run.getStatus(),
                run.getTotalCount() == null ? 0 : run.getTotalCount(),
                run.getSucceededCount() == null ? 0 : run.getSucceededCount(),
                run.getIsolatedCount() == null ? 0 : run.getIsolatedCount(),
                run.getFailedCount() == null ? 0 : run.getFailedCount(),
                run.getCreatedAt(),
                run.getFinishedAt());
    }

    private static int count(List<RubricsRunItemEntity> items, RunItemStatus status) {
        return (int) items.stream().filter(item -> item.getStatus() == status).count();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
