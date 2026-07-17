package com.pixflow.module.vision.analyze;

import java.util.List;
import java.util.Map;

public record VisionAnalysisRequest(
        List<VisionImageRef> images,
        String question,
        VisionTaskType taskType,
        Map<String, Object> context,
        String conversationId,
        String traceId) {

    public VisionAnalysisRequest {
        images = images == null ? List.of() : List.copyOf(images);
        question = question == null ? "" : question.trim();
        taskType = taskType == null ? VisionTaskType.DESCRIBE : taskType;
        context = context == null ? Map.of() : Map.copyOf(context);
        conversationId = normalizeNullable(conversationId);
        traceId = normalizeNullable(traceId);
    }

    public static VisionAnalysisRequest of(List<VisionImageRef> images, String question, VisionTaskType taskType) {
        return new VisionAnalysisRequest(images, question, taskType, Map.of(), null, null);
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
