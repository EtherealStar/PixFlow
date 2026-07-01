package com.pixflow.module.rubrics.template;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.rubrics.error.RubricsErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TemplateRegistry {
    private final Map<String, RubricTemplate> templates = new LinkedHashMap<>();

    public TemplateRegistry(List<RubricTemplate> templates) {
        if (templates != null) {
            for (RubricTemplate template : templates) {
                register(template);
            }
        }
    }

    public void register(RubricTemplate template) {
        templates.put(template.registryKey(), template);
    }

    public List<RubricTemplate> list() {
        return new ArrayList<>(templates.values());
    }

    public List<RubricTemplate> versions(String templateId) {
        return templates.values().stream()
                .filter(template -> template.id().equals(templateId))
                .sorted(Comparator.comparing(RubricTemplate::version))
                .toList();
    }

    public RubricTemplate require(String templateId, String version) {
        RubricTemplate template = templates.get(templateId + ":" + version);
        if (template == null) {
            throw new PixFlowException(RubricsErrorCode.RUBRICS_TEMPLATE_NOT_FOUND,
                    "Rubrics template not found: " + templateId + ":" + version);
        }
        return template;
    }

    public RubricTemplate requireLatest(String templateId) {
        return versions(templateId).stream()
                .max(Comparator.comparing(RubricTemplate::version))
                .orElseThrow(() -> new PixFlowException(RubricsErrorCode.RUBRICS_TEMPLATE_NOT_FOUND,
                        "Rubrics template not found: " + templateId));
    }
}
