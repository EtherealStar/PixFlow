package com.pixflow.app.web.vision;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.ReanalyzeVisualFactsCommand;
import com.pixflow.module.vision.api.ReplaceVisualFactsCommand;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.api.VisualFactsView;
import com.pixflow.module.vision.api.VisualFactsWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/vision/packages/{packageId}/skus/{skuId}")
public class VisionFactsController {
    private final VisualFactsAdministrationService service;

    public VisionFactsController(VisualFactsAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/facts")
    public ApiResponse<VisualFactsResponse> get(
            @PathVariable long packageId, @PathVariable String skuId) {
        return ApiResponse.ok(toResponse(service.get(packageId, skuId)));
    }

    @PutMapping("/facts")
    public ApiResponse<VisualFactsResponse> replace(
            @PathVariable long packageId, @PathVariable String skuId,
            @Valid @RequestBody ReplaceRequest request) {
        return ApiResponse.ok(toResponse(service.replace(packageId, skuId,
                new ReplaceVisualFactsCommand(request.expectedVersion(), request.facts()))));
    }

    @PostMapping("/reanalyze")
    public ApiResponse<VisualFactsResponse> reanalyze(
            @PathVariable long packageId, @PathVariable String skuId,
            @Valid @RequestBody ReanalyzeRequest request) {
        return ApiResponse.ok(toResponse(service.reanalyze(packageId, skuId,
                new ReanalyzeVisualFactsCommand(request.expectedGeneration(), request.requestId()))));
    }

    public record ReplaceRequest(long expectedVersion, @NotNull ProductVisualFacts facts) {
    }

    public record ReanalyzeRequest(
            long expectedGeneration,
            @NotBlank @Size(max = 128) String requestId) {
    }

    private static VisualFactsResponse toResponse(VisualFactsView ownerView) {
        PublicAnalysisStatus status = ownerView.analysisStatus() == AnalysisStatus.EXPIRED
                ? PublicAnalysisStatus.PENDING
                : PublicAnalysisStatus.valueOf(ownerView.analysisStatus().name());
        // EXPIRED 仅用于 owner 恢复调度；浏览器只看到可轮询的 PENDING。
        String failureCode = status == PublicAnalysisStatus.FAILED ? ownerView.failureCode() : null;
        return new VisualFactsResponse(
                ownerView.packageId(), ownerView.skuId(), status, ownerView.analysisGeneration(),
                ownerView.facts(), ownerView.version(), ownerView.writer(), ownerView.updatedAt(), failureCode);
    }

    public enum PublicAnalysisStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record VisualFactsResponse(
            long packageId,
            String skuId,
            PublicAnalysisStatus analysisStatus,
            long analysisGeneration,
            ProductVisualFacts facts,
            long version,
            VisualFactsWriter writer,
            Instant updatedAt,
            String failureCode) {
    }
}
