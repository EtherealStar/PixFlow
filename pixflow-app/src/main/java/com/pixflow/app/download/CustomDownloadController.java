package com.pixflow.app.download;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.task.api.query.DownloadHandle;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomDownloadController {
    private final CustomDownloadService downloadService;

    public CustomDownloadController(CustomDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @PostMapping("/api/downloads/bundle")
    public ApiResponse<DownloadHandle> bundle(@RequestBody CustomBundleRequest request) {
        return ApiResponse.ok(downloadService.build(request));
    }
}
