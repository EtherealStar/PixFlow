package com.pixflow.module.rubrics.template;

import java.util.List;
import com.pixflow.module.rubrics.model.SubjectType;

public record RubricTemplate(
        String id,
        String version,
        String name,
        SubjectType subjectType,
        TemplateLifecycle lifecycle,
        EvaluatorSpec evaluator,
        List<Criterion> criteria) {

    public RubricTemplate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("template id must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("template version must not be blank");
        }
        SemanticVersion.parse(version);
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    public String registryKey() {
        return id + ":" + version;
    }
}
