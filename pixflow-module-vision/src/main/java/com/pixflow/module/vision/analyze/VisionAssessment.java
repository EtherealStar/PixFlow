package com.pixflow.module.vision.analyze;

import java.util.List;

/**
 * 模型输出的稳定结构。字段允许为空，解析降级时仅保证 rawText 与低 confidence。
 */
public record VisionAssessment(
        String composition,
        Boolean backgroundClean,
        Boolean hasWatermark,
        String watermarkPosition,
        Boolean matchesDescription,
        String mismatchReason,
        List<String> sellingPoints,
        List<String> issues,
        Double confidence,
        String rawText) {

    public VisionAssessment {
        sellingPoints = sellingPoints == null ? List.of() : List.copyOf(sellingPoints);
        issues = issues == null ? List.of() : List.copyOf(issues);
        confidence = confidence == null ? null : Math.max(0.0d, Math.min(1.0d, confidence));
    }

    public static VisionAssessment degraded(String rawText) {
        return new VisionAssessment(null, null, null, null, null, null, List.of(), List.of(), 0.2d, rawText);
    }

    public VisionAssessment withAdditionalIssues(List<String> additionalIssues) {
        if (additionalIssues == null || additionalIssues.isEmpty()) {
            return this;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(issues);
        merged.addAll(additionalIssues);
        return new VisionAssessment(
                composition,
                backgroundClean,
                hasWatermark,
                watermarkPosition,
                matchesDescription,
                mismatchReason,
                sellingPoints,
                merged,
                confidence,
                rawText);
    }
}
