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
import com.pixflow.module.task.api.query.TaskStatusView;
import com.pixflow.module.task.api.query.TaskSummary;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.download.DownloadService;
import reactor.core.publisher.Flux;

public class TaskQueryServiceImpl implements TaskQueryService {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final DownloadService downloadService;

    public TaskQueryServiceImpl(ProcessTaskMapper taskMapper,
                                ProcessResultMapper resultMapper,
                                DownloadService downloadService) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.downloadService = downloadService;
    }

    @Override
    public TaskStatusView getStatus(TaskId taskId) {
        ProcessTask task = load(taskId);
        long id = task.getId();
        int success = resultMapper.countByStatus(id, ResultStatus.SUCCESS);
        int failed = resultMapper.countByStatus(id, ResultStatus.FAILED);
        int skipped = resultMapper.countByStatus(id, ResultStatus.SKIPPED);
        return new TaskStatusView(taskId.value(), task.getTaskType(), task.getStatus(),
                value(task.getTotalCount()), success + failed + skipped, failed, skipped,
                task.getLastError(), task.getCreatedAt(), task.getUpdatedAt());
    }

    @Override
    public Flux<ProgressEvent> subscribe(TaskId taskId) {
        TaskStatusView view = getStatus(taskId);
        return Flux.just(new ProgressEvent(view.taskId(), view.total(), view.done(),
                view.failed(), view.skipped(), view.status(), java.time.Instant.now()));
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
    public DownloadHandle getResultDownload(TaskId taskId, ResultSelector selector) {
        load(taskId);
        return downloadService.download(Long.parseLong(taskId.value()), selector);
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
}
