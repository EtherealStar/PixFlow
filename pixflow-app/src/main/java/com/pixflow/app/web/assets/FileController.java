package com.pixflow.app.web.assets;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.file.api.AssetReferenceSource;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.image.AssetImageDetailView;
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
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public final class FileController {
    private final FileService files;

    private final UploadSessionService uploads;

    private final AssetReferenceCatalog references;

    public FileController(
            FileService files, UploadSessionService uploads, AssetReferenceCatalog references) {
        this.files = files;
        this.uploads = uploads;
        this.references = references;
    }

    @PostMapping("/api/files/packages/init")
    public ApiResponse<InitUploadResponse> initUpload(@RequestBody InitUploadRequest request) {
        return ApiResponse.ok(uploads.init(request));
    }

    @GetMapping("/api/files/packages/sessions/{uploadId}")
    public ApiResponse<UploadSessionState> getSession(@PathVariable String uploadId) {
        return ApiResponse.ok(uploads.getSession(uploadId));
    }

    @PutMapping(path = "/api/files/packages/sessions/{uploadId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<PutChunkResponse> putChunk(
            @PathVariable String uploadId,
            @PathVariable int index,
            @RequestHeader("X-Chunk-Hash") String chunkHash,
            @RequestHeader("X-Chunk-Size") long chunkSize,
            HttpServletRequest request) throws IOException {
        try (InputStream input = request.getInputStream()) {
            return ApiResponse.ok(uploads.putChunk(uploadId, index, chunkSize, chunkHash, input));
        }
    }

    @PostMapping("/api/files/packages/sessions/{uploadId}/complete")
    public ApiResponse<CompleteUploadResponse> completeUpload(
            @PathVariable String uploadId,
            @RequestBody(required = false) CompleteUploadRequest request) {
        return ApiResponse.ok(uploads.complete(uploadId, request));
    }

    @DeleteMapping("/api/files/packages/sessions/{uploadId}")
    public ApiResponse<CancelUploadResponse> cancelUpload(@PathVariable String uploadId) {
        return ApiResponse.ok(uploads.cancel(uploadId));
    }

    @GetMapping("/api/asset-references")
    public ApiResponse<PageResponse<AssetReferenceCandidate>> assetReferences(
            @RequestParam(required = false) AssetReferenceSource source,
            @RequestParam(required = false) String parentKey,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(value = "excludeReferenceKey", required = false) List<String> exclusions) {
        return ApiResponse.ok(references.list(source, parentKey, query, page, size, exclusions));
    }

    @GetMapping("/api/files/packages/{packageId}")
    public ApiResponse<AssetPackage> detail(@PathVariable long packageId) {
        return ApiResponse.ok(files.detail(packageId));
    }

    @GetMapping("/api/files/packages")
    public ApiResponse<PageResponse<AssetPackage>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(files.list(page, size));
    }

    @GetMapping("/api/files/packages/{packageId}/errors")
    public ApiResponse<PageResponse<AssetIngestError>> errors(
            @PathVariable long packageId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(files.errors(packageId, page, size));
    }

    @GetMapping("/api/files/packages/{packageId}/skus")
    public ApiResponse<PageResponse<AssetSkuView>> skus(
            @PathVariable long packageId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResponse.ok(files.skus(packageId, page, size));
    }

    @GetMapping("/api/files/images")
    public ApiResponse<PageResponse<AssetImageView>> globalImages(
            @RequestParam(required = false) Long packageId,
            @RequestParam(required = false) String skuId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResponse.ok(files.globalImages(packageId, skuId, query, page, size));
    }

    @GetMapping("/api/files/packages/{packageId}/images")
    public ApiResponse<PageResponse<AssetImageView>> images(
            @PathVariable long packageId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResponse.ok(files.images(packageId, page, size));
    }

    @GetMapping("/api/files/packages/{packageId}/images/{imageId}")
    public ApiResponse<AssetImageDetailView> imageDetail(
            @PathVariable long packageId, @PathVariable long imageId) {
        return ApiResponse.ok(files.imageDetail(packageId, imageId));
    }

    @PatchMapping("/api/files/packages/{packageId}/images/{imageId}")
    public ApiResponse<AssetImageView> renameImage(
            @PathVariable long packageId,
            @PathVariable long imageId,
            @RequestBody RenameRequest request) {
        return ApiResponse.ok(files.renameImage(packageId, imageId, request.displayName()));
    }

    @DeleteMapping("/api/files/packages/{packageId}/images/{imageId}")
    public ApiResponse<Void> deleteImage(@PathVariable long packageId, @PathVariable long imageId) {
        files.deleteImage(packageId, imageId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/files/packages/{packageId}")
    public ApiResponse<AssetPackage> renamePackage(
            @PathVariable long packageId, @RequestBody RenameRequest request) {
        return ApiResponse.ok(files.renamePackage(packageId, request.displayName()));
    }

    @DeleteMapping("/api/files/packages/{packageId}")
    public ApiResponse<Void> delete(@PathVariable long packageId) {
        files.delete(packageId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/files/packages/{packageId}/cancel-extraction")
    public ApiResponse<Void> cancelExtraction(@PathVariable long packageId) {
        files.cancelExtraction(packageId);
        return ApiResponse.ok(null);
    }

    public record RenameRequest(String displayName) {
    }
}
