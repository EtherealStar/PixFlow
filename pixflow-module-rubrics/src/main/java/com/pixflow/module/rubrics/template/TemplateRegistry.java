package com.pixflow.module.rubrics.template;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.rubrics.error.RubricsErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateRegistry {
    private final Map<String, LoadedTemplate> templates = new LinkedHashMap<>();

    public TemplateRegistry(List<LoadedTemplate> loaded) {
        loaded.forEach(this::register);
    }

    private void register(LoadedTemplate candidate) {
        LoadedTemplate existing = templates.putIfAbsent(candidate.template().registryKey(), candidate);
        if (existing != null && !existing.canonicalHash().equals(candidate.canonicalHash())) {
            throw new IllegalStateException("Conflicting rubric template " + candidate.template().registryKey()
                    + " from " + existing.source() + " and " + candidate.source());
        }
    }

    public List<LoadedTemplate> list() { return List.copyOf(templates.values()); }

    public List<LoadedTemplate> versions(String id) {
        return templates.values().stream()
                .filter(value -> value.template().id().equals(id))
                .sorted(java.util.Comparator.comparing(value -> SemanticVersion.parse(value.template().version())))
                .toList();
    }

    public LoadedTemplate require(String id, String version) {
        LoadedTemplate template = templates.get(id + ":" + version);
        if (template == null) {
            throw new PixFlowException(RubricsErrorCode.RUBRICS_TEMPLATE_NOT_FOUND,
                    "Rubrics template not found: " + id + ":" + version);
        }
        return template;
    }
}
