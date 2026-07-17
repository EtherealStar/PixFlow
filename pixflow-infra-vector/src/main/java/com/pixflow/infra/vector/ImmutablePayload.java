package com.pixflow.infra.vector;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutablePayload {
    private ImmutablePayload() {
    }

    static Map<String, Object> copy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> nested.put(String.valueOf(key), copyValue(nestedValue)));
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            iterable.forEach(item -> nested.add(copyValue(item)));
            // Qdrant JSON 数组允许 null，不能使用会拒绝 null 的 List.copyOf。
            return Collections.unmodifiableList(nested);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> nested = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                nested.add(copyValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(nested);
        }
        return value;
    }
}
