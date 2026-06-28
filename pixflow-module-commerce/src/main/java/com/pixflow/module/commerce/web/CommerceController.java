package com.pixflow.module.commerce.web;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.commerce.CommerceService;
import com.pixflow.module.commerce.importer.ImportOptions;
import com.pixflow.module.commerce.importer.ImportReport;
import com.pixflow.module.commerce.query.CommerceQuery;
import com.pixflow.module.commerce.query.CommerceQueryResult;
import com.pixflow.module.commerce.source.ApiImportRequest;
import com.pixflow.module.commerce.source.ImportJobStatusView;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/commerce")
public class CommerceController {
    private final CommerceService commerceService;

    public CommerceController(CommerceService commerceService) {
        this.commerceService = commerceService;
    }

    @PostMapping(value = "/import/local", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportReport> importLocal(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "options", required = false) ImportOptions options) throws IOException {
        return ApiResponse.ok(commerceService.importLocal(file.getInputStream(), file.getOriginalFilename(), options));
    }

    @PostMapping("/import/api")
    public ApiResponse<ImportJobStatusView> importApi(@RequestBody ApiImportRequest request) {
        return ApiResponse.ok(commerceService.startApiImport(request));
    }

    @GetMapping("/import/jobs/{jobId}")
    public ApiResponse<ImportJobStatusView> getJob(@PathVariable long jobId) {
        return ApiResponse.ok(commerceService.getImportJob(jobId));
    }

    @PostMapping("/query")
    public ApiResponse<CommerceQueryResult> query(@RequestBody CommerceQuery query) {
        return ApiResponse.ok(commerceService.query(query));
    }
}
