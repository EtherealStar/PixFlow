package com.pixflow.app.web.outputs;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.module.file.output.GeneratedImageView;
import com.pixflow.module.file.output.OutputConversationSort;
import com.pixflow.module.file.output.OutputConversationView;
import com.pixflow.module.file.output.OutputQueryService;
import com.pixflow.module.file.output.OutputTaskView;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/outputs")
public final class OutputController {
    private final OutputQueryService outputs;

    public OutputController(OutputQueryService outputs) {
        this.outputs = outputs;
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationResponse>> conversations(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) OutputConversationSort sort) {
        Pagination pagination = Pagination.of(page, size);
        return ApiResponse.ok(mapConversations(outputs.conversations(
                pagination.page(), pagination.size(), query, sort)));
    }

    @GetMapping("/conversations/{conversationId}/tasks")
    public ApiResponse<PageResponse<TaskResponse>> tasks(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        Pagination pagination = Pagination.of(page, size);
        return ApiResponse.ok(mapTasks(outputs.tasks(
                conversationId, pagination.page(), pagination.size())));
    }

    @GetMapping("/tasks/{taskId}/images")
    public ApiResponse<PageResponse<ImageResponse>> images(
            @PathVariable String taskId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        Pagination pagination = Pagination.of(page, size == null ? 50L : size);
        return ApiResponse.ok(mapImages(outputs.images(taskId, pagination.page(), pagination.size())));
    }

    @PatchMapping("/images/{imageId}")
    public ApiResponse<ImageResponse> rename(
            @PathVariable String imageId, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(ImageResponse.from(outputs.rename(imageId, request.displayName())));
    }

    @DeleteMapping("/images/{imageId}")
    public ApiResponse<Void> delete(@PathVariable String imageId) {
        outputs.delete(imageId);
        return ApiResponse.ok(null);
    }

    private static PageResponse<ConversationResponse> mapConversations(
            PageResponse<OutputConversationView> page) {
        return PageResponse.of(
                page.records().stream().map(ConversationResponse::from).toList(),
                page.total(), page.page(), page.size());
    }

    private static PageResponse<TaskResponse> mapTasks(PageResponse<OutputTaskView> page) {
        return PageResponse.of(
                page.records().stream().map(TaskResponse::from).toList(),
                page.total(), page.page(), page.size());
    }

    private static PageResponse<ImageResponse> mapImages(PageResponse<GeneratedImageView> page) {
        return PageResponse.of(
                page.records().stream().map(ImageResponse::from).toList(),
                page.total(), page.page(), page.size());
    }

    public record RenameRequest(@NotBlank @Size(max = 255) String displayName) {
    }

    public record ConversationResponse(
            String conversationId, String title, long generatedImageCount, Instant latestGeneratedAt) {
        static ConversationResponse from(OutputConversationView view) {
            return new ConversationResponse(view.conversationId(), view.title(),
                    view.generatedImageCount(), view.latestGeneratedAt());
        }
    }

    public record TaskResponse(
            String taskId, String taskType, long generatedImageCount,
            Instant createdAt, Instant finishedAt) {
        static TaskResponse from(OutputTaskView view) {
            return new TaskResponse(view.taskId(), view.taskType().name(),
                    view.generatedImageCount(), view.createdAt(), view.finishedAt());
        }
    }

    public record ImageResponse(
            String imageId, String referenceKey, String sourceType, String displayName,
            long packageId, String skuId, String conversationId, String taskId,
            String sourceImageId, Integer width, Integer height, Long sizeBytes,
            String contentType, String previewUrl, Instant previewExpiresAt, Instant createdAt) {
        static ImageResponse from(GeneratedImageView view) {
            return new ImageResponse(
                    view.imageId(), view.referenceKey(), view.sourceType(), view.displayName(),
                    view.packageId(), view.skuId(), view.conversationId(), view.taskId(),
                    view.sourceImageId(), view.width(), view.height(), view.sizeBytes(),
                    view.contentType(), view.previewUrl(), view.previewExpiresAt(), view.createdAt());
        }
    }
}
