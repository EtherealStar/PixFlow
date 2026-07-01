package com.pixflow.module.rubrics.run;

import java.util.List;

public record ManualRunRequest(String templateId, String templateVersion, List<Long> resultIds) {
    public ManualRunRequest {
        templateId = templateId == null || templateId.isBlank() ? "default" : templateId;
        resultIds = resultIds == null ? List.of() : List.copyOf(resultIds);
    }
}
