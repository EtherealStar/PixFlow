package com.pixflow.harness.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class PermissionValues {
    private static final Pattern CANONICAL_REFERENCE = Pattern.compile(
            "package:[1-9][0-9]*(?:/image:[1-9][0-9]*|/sku:[A-Za-z0-9._~%-]+)?");

    private static final Set<String> RESERVED_FACT_KEYS = Set.of(
            "userid", "username", "ownerid", "storagekey", "allowed");

    private PermissionValues() {
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    static String requireCanonicalReference(String referenceKey) {
        String value = requireText(referenceKey, "referenceKey");
        if (!CANONICAL_REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("referenceKey 不是 canonical Asset Reference");
        }
        return value;
    }

    static List<String> copyCanonicalReferences(List<String> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("referenceKeys 不能为空");
        }
        List<String> copy = new ArrayList<>(source.size());
        for (String referenceKey : source) {
            copy.add(requireCanonicalReference(referenceKey));
        }
        return List.copyOf(copy);
    }

    static Map<String, Object> copySafeFacts(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = requireText(key, "safeFacts key")
                    .replace("_", "")
                    .toLowerCase(Locale.ROOT);
            if (RESERVED_FACT_KEYS.contains(normalized)) {
                throw new IllegalArgumentException("safeFacts 不得携带权限证明字段: " + key);
            }
            copy.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PermissionValues::immutableValue).toList();
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }
}
