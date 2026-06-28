package com.pixflow.module.file.web;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.UploadPackageResponse;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.pkg.AssetPackage;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
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

    @DeleteMapping("/api/files/packages/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") long id) {
        fileService.delete(id);
        return ApiResponse.ok(null);
    }
}
