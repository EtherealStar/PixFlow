package com.etherealstar.pixflow.module.file.controller;

import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.dto.ResultPreviewResponse;
import com.etherealstar.pixflow.module.file.service.ResultPreviewService;
import com.etherealstar.pixflow.module.task.dto.TaskResultItem;
import com.etherealstar.pixflow.module.task.service.ResultDownloadService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 加工结果图预览、列表与打包下载接口（需求 4.6、4.7、13.1–13.6）。
 *
 * <p>{@code /preview} 返回结果图的可访问 URL；{@code /raw} 流式返回原始图片字节供前端展示；
 * {@code /list} 返回分页结果列表；{@code /download/{taskId}} 流式打包下载任务成功结果图。
 * 结果图或物理文件不存在时由 {@link ResultPreviewService} 抛出 {@code RESULT_NOT_FOUND}。</p>
 */
@RestController
@RequestMapping("/api/asset/result")
public class ResultPreviewController {

    private final ResultPreviewService resultPreviewService;
    private final ResultDownloadService resultDownloadService;
    private final StorageService storageService;

    public ResultPreviewController(ResultPreviewService resultPreviewService,
                                   ResultDownloadService resultDownloadService,
                                   StorageService storageService) {
        this.resultPreviewService = resultPreviewService;
        this.resultDownloadService = resultDownloadService;
        this.storageService = storageService;
    }

    /**
     * 加工结果列表（分页，可选 taskId 筛选，需求 13.1、13.2）。
     *
     * @param page   页码（默认 1）
     * @param size   每页条数（默认 20，取值 1–100）
     * @param taskId 任务 id 筛选（选填）
     */
    @GetMapping("/list")
    public ResponseEntity<PageResponse<TaskResultItem>> list(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size,
            @RequestParam(value = "taskId", required = false) Long taskId) {
        return ResponseEntity.ok(resultDownloadService.listResults(page, size, taskId));
    }

    /**
     * 流式打包下载任务的全部成功结果图（需求 13.3–13.6）。
     *
     * <p>采用 {@link StreamingResponseBody} 逐文件写出 zip，避免将全部结果载入内存。无成功结果时
     * 由 {@link ResultDownloadService#prepareDownloadName} 抛出 {@code NO_DOWNLOADABLE_RESULT}（需求 13.5）。</p>
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("taskId") long taskId) {
        String fileName = resultDownloadService.prepareDownloadName(taskId);
        StreamingResponseBody body = out -> resultDownloadService.streamZip(taskId, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /**
     * 获取结果图预览 URL（需求 4.6、4.7）。
     */
    @GetMapping("/{resultId}/preview")
    public ResponseEntity<ResultPreviewResponse> preview(@PathVariable("resultId") long resultId) {
        return ResponseEntity.ok(resultPreviewService.previewUrl(resultId));
    }

    /**
     * 流式返回结果图原始字节（需求 4.6）。
     */
    @GetMapping("/{resultId}/raw")
    public ResponseEntity<InputStreamResource> raw(@PathVariable("resultId") long resultId) {
        String path = resultPreviewService.resolveOutputPath(resultId);
        MediaType mediaType = mediaTypeOf(path);
        InputStreamResource body = new InputStreamResource(storageService.openInputStream(path));
        return ResponseEntity.ok().contentType(mediaType).body(body);
    }

    /**
     * 依据文件扩展名推断图片 MIME 类型，未知类型回退为通用二进制流。
     */
    private static MediaType mediaTypeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
