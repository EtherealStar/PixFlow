package com.pixflow.module.task.api;

import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.api.query.ResultSelector;
import com.pixflow.module.task.api.query.TaskResultView;
import com.pixflow.module.task.api.query.TaskStatusView;
import com.pixflow.module.task.api.query.TaskSummary;
import reactor.core.publisher.Flux;

public interface TaskQueryService {
    TaskStatusView getStatus(TaskId taskId);

    Flux<ProgressEvent> subscribe(TaskId taskId);

    PageResult<TaskSummary> listByConversation(String conversationId, PageQuery query);

    PageResult<TaskResultView> listResults(TaskId taskId, PageQuery query);

    PageResult<TaskResultView> listConversationImages(String conversationId, PageQuery query);

    DownloadHandle getResultDownload(TaskId taskId, ResultSelector selector);

    void deleteResult(TaskId taskId, String resultId);

    TaskResultView renameResult(TaskId taskId, String resultId, String displayName);
}
