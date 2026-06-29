package com.pixflow.module.vision.enrich;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.module.vision.VisionService;
import com.pixflow.module.vision.analyze.VisionAnalysisRequest;
import com.pixflow.module.vision.analyze.VisionAnalysisResult;
import com.pixflow.module.vision.analyze.VisionAssessment;
import com.pixflow.module.vision.analyze.VisionImageRef;
import com.pixflow.module.vision.analyze.VisionTaskType;
import com.pixflow.module.vision.config.VisionProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ProductCopyExtractor {
    private final VisionService visionService;
    private final VisionProperties properties;

    public ProductCopyExtractor(VisionService visionService, VisionProperties properties) {
        this.visionService = Objects.requireNonNull(visionService, "visionService");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public ProductCopyDraft extract(long packageId, String skuId, List<AssetImageRow> rows) {
        List<VisionImageRef> refs = rows.stream()
                .limit(Math.max(1, properties.getEnrich().getImagesPerSku()))
                .map(row -> VisionImageRef.of(BucketType.PACKAGES, row.getMinioKey(), skuId, row.getViewId(), "sku image"))
                .toList();
        VisionAnalysisResult result = visionService.analyze(new VisionAnalysisRequest(
                refs,
                "Extract product copy fields for this SKU. Return product name, keywords, and a concise product description.",
                VisionTaskType.SELLING_POINTS,
                Map.of("packageId", packageId, "skuId", skuId),
                null,
                null));
        return fromAssessment(result.assessment(), skuId);
    }

    private ProductCopyDraft fromAssessment(VisionAssessment assessment, String skuId) {
        String keywords = assessment.sellingPoints().stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(","));
        String description = firstNonBlank(assessment.composition(), assessment.rawText());
        String productName = firstNonBlank(extractProductName(assessment.rawText()), skuId);
        return new ProductCopyDraft(productName, keywords.isBlank() ? null : keywords, description);
    }

    private String extractProductName(String rawText) {
        if (rawText == null) {
            return null;
        }
        int index = rawText.indexOf("productName");
        if (index < 0) {
            return null;
        }
        int colon = rawText.indexOf(':', index);
        if (colon < 0) {
            return null;
        }
        int comma = rawText.indexOf(',', colon);
        int end = comma < 0 ? Math.min(rawText.length(), colon + 80) : comma;
        String candidate = rawText.substring(colon + 1, end).replace("\"", "").replace("}", "").trim();
        return candidate.isBlank() ? null : candidate;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }
}
