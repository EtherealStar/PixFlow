package com.pixflow.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a local .env file from the workspace root or app module root into Spring Environment.
 */
public final class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "pixflowDotenv";
    private static final List<Path> CANDIDATES = List.of(
            Path.of(".env"),
            Path.of("pixflow-app", ".env"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (Path candidate : CANDIDATES) {
            Path path = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Map<String, Object> properties = loadDotenv(path);
            if (properties.isEmpty()) {
                continue;
            }
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME + ":" + path, properties));
            return;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static Map<String, Object> loadDotenv(Path path) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                if (key.isEmpty()) {
                    continue;
                }
                String value = line.substring(separator + 1).trim();
                properties.put(key, stripQuotes(value));
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return properties;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
