package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.template.SemanticVersion;

public record TemplateRef(String templateId, String semanticVersion) {

    public TemplateRef {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("template id must not be blank");
        }
        if (semanticVersion == null || semanticVersion.isBlank()) {
            throw new IllegalArgumentException("template version must not be blank");
        }
        SemanticVersion.parse(semanticVersion);
    }
}
