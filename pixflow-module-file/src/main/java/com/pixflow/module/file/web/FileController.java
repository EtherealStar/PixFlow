package com.pixflow.module.file.web;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.file.api.AssetReferenceSource;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.image.AssetImageView;
import com.pixflow.module.file.image.AssetSkuView;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class FileController {
    private final FileService fileService;

    private final UploadSessionService uploadSessionService;

    private final AssetReferenceCatalog referenceCatalog;

    public FileController(FileService fileService, UploadSessionService uploadSessionService,
                          AssetReferenceCatalog referenceCatalog) {
        this.fileService = fileService;
        this.uploadSessionService = uploadSessionService;
        this.referenceCatalog = referenceCatalog;
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

    @GetMapping("/api/asset-references")
    public ApiResponse<PageResponse<AssetReferenceCandidate>> assetReferences(
            @RequestParam(value = "source", required = false) AssetReferenceSource source,
            @RequestParam(value = "parentKey", required = false) String parentKey,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "50") long size,
            @RequestParam(value = "excludeReferenceKey", required = false) java.util.List<String> exclusions) {
        return ApiResponse.ok(referenceCatalog.list(source, parentKey, query, page, size, exclusions));
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

    @GetMapping("/api/files/packages/{id}/skus")
    public ApiResponse<PageResponse<AssetSkuView>> skus(
            @PathVariable("id") long id,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        return ApiResponse.ok(fileService.skus(id, page, size));
    }

    @GetMapping("/api/files/images")
    public ApiResponse<PageResponse<AssetImageView>> globalImages(
            @RequestParam(value = "packageId", required = false) Long packageId,
            @RequestParam(value = "skuId", required = false) String skuId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        return ApiResponse.ok(fileService.globalImages(packageId, skuId, query, page, size));
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

    @PatchMapping("/api/files/packages/{id}")
    public ApiResponse<AssetPackage> renamePackage(
            @PathVariable("id") long id, @RequestBody RenameImageRequest request) {
        return ApiResponse.ok(fileService.renamePackage(id, request.displayName()));
    }

    @PostMapping("/api/files/packages/{id}/cancel-extraction")
    public ApiResponse<Void> cancelExtraction(@PathVariable("id") long id) {
        fileService.cancelExtraction(id);
        return ApiResponse.ok(null);
    }

    public record RenameImageRequest(String displayName) {
    }
}
