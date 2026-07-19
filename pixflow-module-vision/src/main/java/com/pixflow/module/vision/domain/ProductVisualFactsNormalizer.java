package com.pixflow.module.vision.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.vision.api.CommonVisualFacts;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.VisualAttribute;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 在持久化边界统一执行 trim、去重、数量和 JSON 大小限制。
 */
public final class ProductVisualFactsNormalizer {
    public static final int MAX_JSON_BYTES = 64 * 1024;

    private static final int MAX_COMMON_TEXT = 200;

    private static final int MAX_COMMON_ITEMS = 32;

    private static final int MAX_ATTRIBUTE_NAME = 64;

    private static final int MAX_ATTRIBUTE_VALUE = 256;

    private static final int MAX_ATTRIBUTES = 32;

    private static final int MAX_DIAGNOSTIC_ITEMS = 16;

    private static final int MAX_DIAGNOSTIC_TEXT = 500;

    private final ObjectMapper objectMapper;

    public ProductVisualFactsNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ProductVisualFacts normalize(ProductVisualFacts input) {
        Objects.requireNonNull(input, "input");
        CommonVisualFacts common = input.common();
        ProductVisualFacts normalized = new ProductVisualFacts(
                new CommonVisualFacts(
                        scalar(common.categoryAppearance(), MAX_COMMON_TEXT, "categoryAppearance"),
                        strings(common.dominantColors(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "dominantColors"),
                        strings(common.visibleMaterials(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "visibleMaterials"),
                        strings(common.shapes(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "shapes"),
                        strings(common.visibleComponents(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "visibleComponents"),
                        strings(common.patterns(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "patterns"),
                        strings(common.visibleText(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "visibleText"),
                        scalar(common.background(), MAX_COMMON_TEXT, "background"),
                        strings(common.viewTypes(), MAX_COMMON_ITEMS, MAX_COMMON_TEXT, "viewTypes")),
                attributes(input.attributes()),
                strings(input.limitations(), MAX_DIAGNOSTIC_ITEMS, MAX_DIAGNOSTIC_TEXT, "limitations"),
                strings(input.conflicts(), MAX_DIAGNOSTIC_ITEMS, MAX_DIAGNOSTIC_TEXT, "conflicts"));
        ensureJsonBound(normalized);
        return normalized;
    }

    public String write(ProductVisualFacts input) {
        try {
            return objectMapper.writeValueAsString(normalize(input));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("facts cannot be serialized", exception);
        }
    }

    public ProductVisualFacts read(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("facts JSON exceeds the allowed size");
        }
        try {
            return normalize(objectMapper.readValue(json, ProductVisualFacts.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("facts JSON does not match the closed contract", exception);
        }
    }

    private List<VisualAttribute> attributes(List<VisualAttribute> values) {
        if (values.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("attributes exceeds 32 items");
        }
        List<VisualAttribute> normalized = new ArrayList<>();
        LinkedHashSet<VisualAttribute> seen = new LinkedHashSet<>();
        for (VisualAttribute value : values) {
            if (value == null) {
                throw new IllegalArgumentException("attributes contains null");
            }
            String name = required(value.name(), MAX_ATTRIBUTE_NAME, "attribute.name");
            String attributeValue = required(value.value(), MAX_ATTRIBUTE_VALUE, "attribute.value");
            VisualAttribute attribute = new VisualAttribute(name, attributeValue);
            if (seen.add(attribute)) {
                normalized.add(attribute);
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> strings(List<String> values, int maxItems, int maxLength, String field) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = scalar(value, maxLength, field);
            if (text != null) {
                normalized.add(text);
            }
        }
        if (normalized.size() > maxItems) {
            throw new IllegalArgumentException(field + " exceeds " + maxItems + " items");
        }
        return List.copyOf(normalized);
    }

    private String required(String value, int maxLength, String field) {
        String normalized = scalar(value, maxLength, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private String scalar(String value, int maxLength, String field) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private void ensureJsonBound(ProductVisualFacts facts) {
        try {
            int bytes = objectMapper.writeValueAsBytes(facts).length;
            if (bytes > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("facts JSON exceeds 64 KiB");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("facts cannot be serialized", exception);
        }
    }
}
