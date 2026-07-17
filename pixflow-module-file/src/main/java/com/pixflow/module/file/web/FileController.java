package com.pixflow.module.file.web;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.UploadPackageResponse;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.image.AssetImageView;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.upload.CancelUploadResponse;
import com.pixflow.module.file.upload.CompleteUploadRequest;
import com.pixflow.module.file.upload.CompleteUploadResponse;
import com.pixflow.module.file.upload.InitUploadRequest;
import com.pixflow.module.file.upload.InitUploadResponse;
import com.pixflow.module.file.upload.PutChunkResponse;
import com.pixflow.module.file.upload.UploadSessionService;
import com.pixflow.module.file.upload.UploadSessionState;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
public class FileController {
    private final FileService fileService;

    private final UploadSessionService uploadSessionService;

    public FileController(FileService fileService, UploadSessionService uploadSessionService) {
        this.fileService = fileService;
        this.uploadSessionService = uploadSessionService;
    }

    @PostMapping("/api/files/packages/init")
    public ApiResponse<InitUploadResponse> initUpload(@RequestBody InitUploadRequest request) {
        return ApiResponse.ok(uploadSessionService.init(request));
    }

    @GetMapping("/api/files/packages/sessions/{uploadId}")
    public ApiResponse<UploadSessionState> getSession(@PathVariable String uploadId) {
        return ApiResponse.ok(uploadSessionService.getSession(uploadId));
    }

    @PutMapping(
            path = "/api/files/packages/sessions/{uploadId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<PutChunkResponse> putChunk(
            @PathVariable String uploadId,
            @PathVariable int index,
            @RequestHeader("X-Chunk-Hash") String chunkHash,
            @RequestHeader("X-Chunk-Size") long chunkSize,
            HttpServletRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream()) {
            return ApiResponse.ok(uploadSessionService.putChunk(uploadId, index, chunkSize, chunkHash, inputStream));
        }
    }

    @PostMapping("/api/files/packages/sessions/{uploadId}/complete")
    public ApiResponse<CompleteUploadResponse> completeUpload(
            @PathVariable String uploadId,
            @RequestBody(required = false) CompleteUploadRequest request) {
        return ApiResponse.ok(uploadSessionService.complete(uploadId, request));
    }

    @DeleteMapping("/api/files/packages/sessions/{uploadId}")
    public ApiResponse<CancelUploadResponse> cancelUpload(@PathVariable String uploadId) {
        return ApiResponse.ok(uploadSessionService.cancel(uploadId));
    }

    @PostMapping(path = "/api/files/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadPackageResponse> upload(
            @RequestPart("zip") MultipartFile zip,
            @RequestPart(value = "doc", required = false) MultipartFile doc) throws IOException {
        return ApiResponse.ok(fileService.upload(zip, doc));
    }

    @GetMapping("/api/files/packages/{id}")
    public ApiResponse<AssetPackage> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(fileService.detail(id));
    }

    @GetMapping("/api/files/packages")
    public ApiResponse<PageResponse<AssetPackage>> list(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return ApiResponse.ok(fileService.list(page, size));
    }

    @GetMapping("/api/files/packages/{id}/errors")
    public ApiResponse<PageResponse<AssetIngestError>> errors(
            @PathVariable("id") long id,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return ApiResponse.ok(fileService.errors(id, page, size));
    }

    @GetMapping("/api/files/packages/{id}/images")
    public ApiResponse<PageResponse<AssetImageView>> images(
            @PathVariable("id") long id,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        return ApiResponse.ok(fileService.images(id, page, size));
    }

    @DeleteMapping("/api/files/packages/{id}/images/{imageId}")
    public ApiResponse<Void> deleteImage(@PathVariable("id") long id, @PathVariable("imageId") long imageId) {
        fileService.deleteImage(id, imageId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/files/packages/{id}/images/{imageId}")
    public ApiResponse<AssetImageView> renameImage(
            @PathVariable("id") long id,
            @PathVariable("imageId") long imageId,
            @RequestBody RenameImageRequest request) {
        return ApiResponse.ok(fileService.renameImage(id, imageId, request.displayName()));
    }

    @DeleteMapping("/api/files/packages/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") long id) {
        fileService.delete(id);
        return ApiResponse.ok(null);
    }

    public record RenameImageRequest(String displayName) {
    }
}
