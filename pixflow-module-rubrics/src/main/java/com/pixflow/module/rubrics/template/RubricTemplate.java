package com.pixflow.module.rubrics.template;

import java.util.List;

public record RubricTemplate(
        String id,
        String version,
        String name,
        List<RubricDomain> domains) {

    public RubricTemplate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("template id must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("template version must not be blank");
        }
        domains = domains == null ? List.of() : List.copyOf(domains);
    }

    public String registryKey() {
        return id + ":" + version;
    }
}
