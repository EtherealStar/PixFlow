package com.pixflow.app.task;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskQueryController {
    private final TaskQueryService taskQueryService;

    private final TaskCommandService taskCommandService;

    private final AssetReferenceCatalog assetReferences;

    public TaskQueryController(TaskQueryService taskQueryService, TaskCommandService taskCommandService,
                               AssetReferenceCatalog assetReferences) {
        this.taskQueryService = taskQueryService;
        this.taskCommandService = taskCommandService;
        this.assetReferences = assetReferences;
    }

    @PostMapping("/api/tasks/{taskId}/retry-failed")
    public ApiResponse<RetryTaskResponse> retryFailed(@PathVariable String taskId) {
        return ApiResponse.ok(taskCommandService.retryFailed(
                new RetryFailedTaskCommand(new TaskId(taskId))));
    }

    @GetMapping("/api/tasks/{taskId}")
    public ApiResponse<TaskStatusView> status(@PathVariable String taskId) {
        return ApiResponse.ok(taskQueryService.getStatus(new TaskId(taskId)));
    }

    @GetMapping("/api/conversations/{conversationId}/tasks")
    public ApiResponse<PageResult<TaskSummary>> conversationTasks(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(toPublicPage(taskQueryService.listByConversation(
                conversationId, publicPage(page, size))));
    }

    @GetMapping("/api/tasks/{taskId}/results")
    public ApiResponse<PageResult<TaskResultView>> results(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(toPublicPage(
                taskQueryService.listResults(new TaskId(taskId), publicPage(page, size))));
    }

    @GetMapping("/api/tasks/{taskId}/outputs")
    public ApiResponse<PageResponse<AssetReferenceCandidate>> outputs(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(value = "excludeReferenceKey", required = false)
            java.util.List<String> exclusions) {
        // Generated Image 由 File lineage 查询，不依赖可清理的 process_result 行。
        return ApiResponse.ok(assetReferences.listGeneratedByTaskId(
                Long.parseLong(taskId), page, size, exclusions));
    }

    @GetMapping("/api/conversations/{conversationId}/images")
    public ApiResponse<PageResult<TaskResultView>> conversationImages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(toPublicPage(taskQueryService.listConversationImages(
                conversationId, publicPage(page, size))));
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

    private static PageQuery publicPage(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1-based");
        }
        // Task 内部仍使用 0-based PageQuery，HTTP 边界统一转换为公开的 1-based 页码。
        return new PageQuery(page - 1, size);
    }

    private static <T> PageResult<T> toPublicPage(PageResult<T> result) {
        return new PageResult<>(result.records(), result.total(), result.page() + 1, result.size());
    }
}
