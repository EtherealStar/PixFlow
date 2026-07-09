package com.pixflow.module.task.internal.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.api.query.ResultSelector;
import com.pixflow.module.task.api.query.TaskResultView;
import com.pixflow.module.task.api.query.TaskStatusView;
import com.pixflow.module.task.api.query.TaskSummary;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.UnitKind;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.download.DownloadService;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

public class TaskQueryServiceImpl implements TaskQueryService {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final DownloadService downloadService;
    private final Clock clock;

    public TaskQueryServiceImpl(ProcessTaskMapper taskMapper,
                                ProcessResultMapper resultMapper,
                                DownloadService downloadService,
                                Clock clock) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.downloadService = downloadService;
        this.clock = clock;
    }

    @Override
    public TaskStatusView getStatus(TaskId taskId) {
        ProcessTask task = load(taskId);
        long id = task.getId();
        int success = resultMapper.countByStatus(id, ResultStatus.SUCCESS);
        int failed = resultMapper.countByStatus(id, ResultStatus.FAILED);
        int skipped = resultMapper.countByStatus(id, ResultStatus.SKIPPED);
        int total = value(task.getTotalCount());
        int done = success + failed + skipped;
        return new TaskStatusView(taskId.value(), task.getTaskType(), task.getStatus(),
                new TaskStatusView.Progress(done, total, failed), skipped,
                task.getLastError(), task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
    }

    @Override
    public Flux<ProgressEvent> subscribe(TaskId taskId) {
        TaskStatusView view = getStatus(taskId);
        return Flux.just(new ProgressEvent(view.taskId(), view.progress().total(), view.progress().done(),
                view.progress().failed(), view.skipped(), view.status(), java.time.Instant.now()));
    }

    @Override
    public PageResult<TaskSummary> listByConversation(String conversationId, PageQuery query) {
        Page<ProcessTask> page = taskMapper.selectPage(new Page<>(query.page() + 1L, query.size()),
                new LambdaQueryWrapper<ProcessTask>()
                        .eq(ProcessTask::getConversationId, conversationId)
                        .orderByDesc(ProcessTask::getCreatedAt));
        return new PageResult<>(
                page.getRecords().stream().map(task -> new TaskSummary(
                        task.getId().toString(), task.getTaskType(), task.getStatus(),
                        value(task.getTotalCount()), value(task.getDoneCount()), 0,
                        task.getCreatedAt(), task.getFinishedAt())).toList(), page.getTotal(),
                query.page(), query.size());
    }

    @Override
    public PageResult<TaskResultView> listResults(TaskId taskId, PageQuery query) {
        ProcessTask task = load(taskId);
        long offset = (long) query.page() * query.size();
        var records = resultMapper.pageVisibleByTaskId(task.getId(), offset, query.size());
        return new PageResult<>(records.stream().map(result -> toView(task, result)).toList(),
                resultMapper.countVisibleByTaskId(task.getId()), query.page(), query.size());
    }

    @Override
    public PageResult<TaskResultView> listConversationImages(String conversationId, PageQuery query) {
        long offset = (long) query.page() * query.size();
        var records = resultMapper.pageConversationImages(conversationId, offset, query.size());
        Map<Long, ProcessTask> tasks = new HashMap<>();
        return new PageResult<>(records.stream().map(result -> {
                    ProcessTask task = tasks.computeIfAbsent(result.getTaskId(),
                            id -> load(new TaskId(String.valueOf(id))));
                    return toView(task, result);
                }).toList(),
                resultMapper.countConversationImages(conversationId), query.page(), query.size());
    }

    @Override
    public DownloadHandle getResultDownload(TaskId taskId, ResultSelector selector) {
        load(taskId);
        return downloadService.download(Long.parseLong(taskId.value()), selector);
    }

    @Override
    public void deleteResult(TaskId taskId, String resultId) {
        ProcessTask task = load(taskId);
        long parsedResultId = parseLong(resultId);
        ProcessResult result = resultMapper.selectById(parsedResultId);
        if (result == null || !task.getId().equals(result.getTaskId())) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_NOT_FOUND, "task result not found: " + resultId);
        }
        // 删除只隐藏 process_result，不删除对象存储字节，保证历史任务和评分证据仍可追溯。
        resultMapper.softDelete(task.getId(), parsedResultId, clock.instant());
    }

    @Override
    public TaskResultView renameResult(TaskId taskId, String resultId, String displayName) {
        ProcessTask task = load(taskId);
        long parsedResultId = parseLong(resultId);
        String normalized = normalizeDisplayName(displayName);
        int updated = resultMapper.updateDisplayName(task.getId(), parsedResultId, normalized);
        if (updated == 0) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_NOT_FOUND, "task result not found: " + resultId);
        }
        ProcessResult result = resultMapper.selectById(parsedResultId);
        result.setDisplayName(normalized);
        return toView(task, result);
    }

    private ProcessTask load(TaskId taskId) {
        ProcessTask task = taskMapper.selectById(Long.parseLong(taskId.value()));
        if (task == null) {
            throw new PixFlowException(TaskErrorCode.TASK_NOT_FOUND, "task not found: " + taskId.value());
        }
        return task;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private TaskResultView toView(ProcessTask task, ProcessResult result) {
        DownloadHandle handle = null;
        if (result.getStatus() == ResultStatus.SUCCESS && result.getOutputMinioKey() != null) {
            handle = downloadService.downloadResult(result);
        }
        Long size = result.getBytesOut();
        return new TaskResultView(
                String.valueOf(result.getId()),
                String.valueOf(result.getTaskId()),
                task.getConversationId(),
                result.getStatus(),
                result.getKind(),
                result.getImageId(),
                result.getSkuId(),
                result.getGroupKey(),
                result.getViewId(),
                result.getBranchId(),
                resultName(result),
                result.getDisplayName(),
                size,
                handle == null ? null : handle.url(),
                result.getCreatedAt(),
                result.getFinishedAt(),
                result.getErrorMsg());
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_NOT_FOUND, "task result not found: " + value, ex);
        }
    }

    private static String resultName(ProcessResult result) {
        if (result.getDisplayName() != null && !result.getDisplayName().isBlank()) {
            return result.getDisplayName();
        }
        String suffix = result.getKind() == UnitKind.GENERATIVE ? "generated" : "result";
        String member = result.getGroupKey() != null ? result.getGroupKey() : result.getImageId();
        String branch = result.getBranchId() == null ? "default" : result.getBranchId();
        return "%s_%s_%s.png".formatted(member == null ? "item" : member, branch, suffix);
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
                || normalized.toLowerCase(Locale.ROOT).contains("..")) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_NAME_INVALID, "invalid task result display name");
        }
        return normalized;
    }
}
