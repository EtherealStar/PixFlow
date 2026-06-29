package com.pixflow.module.vision.analyze;

import com.pixflow.infra.ai.model.TokenUsage;
import java.util.Objects;

public record VisionAnalysisResult(
        VisionAssessment assessment,
        boolean parseDegraded,
        TokenUsage usage,
        int imagesSent) {

    public VisionAnalysisResult {
        assessment = Objects.requireNonNull(assessment, "assessment");
        usage = usage == null ? new TokenUsage(0, 0, 0) : usage;
        if (imagesSent < 0) {
            throw new IllegalArgumentException("imagesSent must be non-negative");
        }
    }
}
