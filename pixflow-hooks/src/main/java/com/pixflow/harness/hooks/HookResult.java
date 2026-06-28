package com.pixflow.harness.hooks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

public record HookResult(
        String blockingReason,
        Map<String, Object> updatedInput,
        Map<String, Object> metadata) {

    public HookResult {
        blockingReason = StringUtils.hasText(blockingReason) ? blockingReason : null;
        updatedInput = immutableCopy(updatedInput);
        metadata = immutableCopy(metadata);
    }

    public static HookResult noop() {
        return new HookResult(null, Map.of(), Map.of());
    }

    public static HookResult block(String reason) {
        return new HookResult(reason, Map.of(), Map.of());
    }

    public static HookResult rewrite(Map<String, Object> updatedInput) {
        return new HookResult(null, updatedInput, Map.of());
    }

    public static HookResult withMetadata(Map<String, Object> metadata) {
        return new HookResult(null, Map.of(), metadata);
    }

    public boolean blocked() {
        return blockingReason != null;
    }

    public boolean inputRewritten() {
        return !updatedInput.isEmpty();
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
