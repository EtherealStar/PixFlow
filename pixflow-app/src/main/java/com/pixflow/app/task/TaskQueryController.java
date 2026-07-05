package com.pixflow.app.task;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.api.query.ResultSelector;
import com.pixflow.module.task.api.query.TaskResultView;
import com.pixflow.module.task.api.query.TaskStatusView;
import com.pixflow.module.task.api.query.TaskSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskQueryController {
    private final TaskQueryService taskQueryService;

    public TaskQueryController(TaskQueryService taskQueryService) {
        this.taskQueryService = taskQueryService;
    }

    @GetMapping("/api/tasks/{taskId}")
    public ApiResponse<TaskStatusView> status(@PathVariable String taskId) {
        return ApiResponse.ok(taskQueryService.getStatus(new TaskId(taskId)));
    }

    @GetMapping("/api/conversations/{conversationId}/tasks")
    public ApiResponse<PageResult<TaskSummary>> conversationTasks(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskQueryService.listByConversation(conversationId, new PageQuery(page, size)));
    }

    @GetMapping("/api/tasks/{taskId}/results")
    public ApiResponse<PageResult<TaskResultView>> results(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(taskQueryService.listResults(new TaskId(taskId), new PageQuery(page, size)));
    }

    @GetMapping("/api/conversations/{conversationId}/images")
    public ApiResponse<PageResult<TaskResultView>> conversationImages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(taskQueryService.listConversationImages(conversationId, new PageQuery(page, size)));
    }

    @GetMapping("/api/tasks/{taskId}/downloads")
    public ApiResponse<DownloadHandle> download(@PathVariable String taskId, @RequestParam String resultId) {
        return ApiResponse.ok(taskQueryService.getResultDownload(new TaskId(taskId),
                ResultSelector.byResultId(Long.parseLong(resultId))));
    }

    @GetMapping("/api/tasks/{taskId}/downloads/bundle")
    public ApiResponse<DownloadHandle> bundle(@PathVariable String taskId) {
        return ApiResponse.ok(taskQueryService.getResultDownload(new TaskId(taskId),
                ResultSelector.allResultsBundle()));
    }

    @DeleteMapping("/api/tasks/{taskId}/results/{resultId}")
    public ApiResponse<Void> deleteResult(@PathVariable String taskId, @PathVariable String resultId) {
        taskQueryService.deleteResult(new TaskId(taskId), resultId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/tasks/{taskId}/results/{resultId}")
    public ApiResponse<TaskResultView> renameResult(
            @PathVariable String taskId,
            @PathVariable String resultId,
            @RequestBody RenameResultRequest request) {
        return ApiResponse.ok(taskQueryService.renameResult(new TaskId(taskId), resultId, request.displayName()));
    }

    public record RenameResultRequest(String displayName) {
    }
}
