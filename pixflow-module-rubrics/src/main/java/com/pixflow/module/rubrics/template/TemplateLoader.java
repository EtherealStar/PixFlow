package com.pixflow.module.rubrics.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class TemplateLoader {
    private final ObjectMapper yamlMapper = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules();

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private final TemplateValidator validator;

    public TemplateLoader(TemplateValidator validator) {
        this.validator = validator;
    }

    public List<LoadedTemplate> load(String classpathPrefix, String userDirectory) {
        List<LoadedTemplate> loaded = new ArrayList<>();
        loaded.addAll(loadClasspath(classpathPrefix));
        if (userDirectory != null && !userDirectory.isBlank()) {
            Path directory = Path.of(userDirectory);
            if (Files.isDirectory(directory)) {
                try (var paths = Files.list(directory)) {
                    paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                            .sorted().map(this::read).forEach(loaded::add);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to scan rubric templates from " + directory, ex);
                }
            }
        }
        return List.copyOf(loaded);
    }

    private List<LoadedTemplate> loadClasspath(String prefix) {
        String path = prefix == null || prefix.isBlank() ? "rubrics/templates/" : prefix;
        try {
            return Arrays.stream(resolver.getResources("classpath*:" + path.replace('\\', '/') + "*.yaml"))
                    .map(this::read).toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan classpath rubric templates", ex);
        }
    }

    private LoadedTemplate read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            return validated(yamlMapper.readValue(stream, RubricTemplate.class), resource.getDescription());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load rubric template " + resource, ex);
        }
    }

    private LoadedTemplate read(Path path) {
        try (InputStream stream = Files.newInputStream(path)) {
            return validated(yamlMapper.readValue(stream, RubricTemplate.class), path.toString());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load rubric template " + path, ex);
        }
    }

    private LoadedTemplate validated(RubricTemplate template, String source) {
        return new LoadedTemplate(template, validator.validateAndHash(template), source);
    }
}
