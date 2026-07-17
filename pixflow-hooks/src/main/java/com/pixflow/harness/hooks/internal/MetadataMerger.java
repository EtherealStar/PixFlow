package com.pixflow.harness.hooks.internal;

import com.pixflow.harness.hooks.error.HookError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MetadataMerger {
    public static final String HOOK_ERRORS_KEY = "hookErrors";

    public static final String CALLBACK_HOOK_ERRORS_KEY = "callbackMetadata.hookErrors";

    private MetadataMerger() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (incoming == null || incoming.isEmpty()) {
            return merged;
        }
        incoming.forEach((key, value) -> {
            // hookErrors 是 dispatcher 保留键，callback 只能被迁移，不能伪造系统异常。
            String targetKey = HOOK_ERRORS_KEY.equals(key) ? CALLBACK_HOOK_ERRORS_KEY : key;
            mergeValue(merged, targetKey, value);
        });
        return merged;
    }

    public static Map<String, Object> appendHookError(Map<String, Object> current, HookError error) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        mergeValue(merged, HOOK_ERRORS_KEY, Objects.requireNonNull(error, "error"));
        return merged;
    }

    private static void mergeValue(Map<String, Object> target, String key, Object value) {
        if (!target.containsKey(key)) {
            target.put(key, value);
            return;
        }
        Object existing = target.get(key);
        if (Objects.equals(existing, value)) {
            return;
        }
        if (existing instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.add(value);
            target.put(key, List.copyOf(copy));
            return;
        }
        target.put(key, List.of(existing, value));
    }
}
