package com.pixflow.module.rubrics.rule;

import com.pixflow.module.task.domain.model.ProcessResult;
import java.util.Map;

public record RuleCheckInput(
        Long resultId,
        ProcessResult result,
        byte[] imageBytes,
        Map<String, Object> params,
        Map<String, Object> taskContext) {

    public RuleCheckInput {
        params = params == null ? Map.of() : Map.copyOf(params);
        taskContext = taskContext == null ? Map.of() : Map.copyOf(taskContext);
        imageBytes = imageBytes == null ? null : imageBytes.clone();
    }

    @Override
    public byte[] imageBytes() {
        return imageBytes == null ? null : imageBytes.clone();
    }
}
