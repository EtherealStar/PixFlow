package com.pixflow.module.rubrics.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class TemplateLoader {
    private final ObjectMapper yamlMapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public TemplateLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    public List<RubricTemplate> loadClasspath(String classpathPrefix) {
        String prefix = classpathPrefix == null || classpathPrefix.isBlank() ? "rubrics/templates/" : classpathPrefix;
        String pattern = "classpath*:" + prefix.replace('\\', '/') + "*.yaml";
        try {
            Resource[] resources = resolver.getResources(pattern);
            return java.util.Arrays.stream(resources)
                    .map(this::read)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan rubric templates from " + pattern, ex);
        }
    }

    private RubricTemplate read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            return yamlMapper.readValue(stream, RubricTemplate.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load rubric template " + resource, ex);
        }
    }
}
