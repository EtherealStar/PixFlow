package com.pixflow.harness.loop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MetadataValues {
    private MetadataValues() {
    }

    public static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        source.forEach((key, value) -> copy.put(validateKey(key), normalizeValue(value, seen)));
        return Collections.unmodifiableMap(copy);
    }

    public static Object normalizeValue(Object value) {
        return normalizeValue(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object normalizeValue(Object value, Set<Object> seen) {
        if (value == null) {
            throw new IllegalArgumentException("metadata container values must not be null");
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            if (value instanceof Double d && !Double.isFinite(d)) {
                throw new IllegalArgumentException("metadata floating point numbers must be finite");
            }
            if (value instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalArgumentException("metadata floating point numbers must be finite");
            }
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> map) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("metadata must not contain cycles");
            }
            try {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(validateKey(k), normalizeValue(v, seen)));
                return Collections.unmodifiableMap(copy);
            } finally {
                seen.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("metadata must not contain cycles");
            }
            try {
                Set<Object> copy = new LinkedHashSet<>();
                for (Object item : set) {
                    copy.add(normalizeValue(item, seen));
                }
                return Collections.unmodifiableSet(copy);
            } finally {
                seen.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("metadata must not contain cycles");
            }
            try {
                List<Object> copy = new ArrayList<>();
                for (Object item : iterable) {
                    copy.add(normalizeValue(item, seen));
                }
                return List.copyOf(copy);
            } finally {
                seen.remove(value);
            }
        }
        if (value != null && value.getClass().isArray()) {
            throw new IllegalArgumentException("metadata arrays must be converted to List explicitly");
        }
        throw new IllegalArgumentException("metadata value is not JSON-serializable: " + value.getClass().getName());
    }

    public static String validateKey(Object key) {
        if (!(key instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("metadata key must be a non-blank string");
        }
        return text;
    }
}
