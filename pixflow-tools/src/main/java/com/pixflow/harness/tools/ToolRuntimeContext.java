package com.pixflow.harness.tools;

import java.util.Map;

/**
 * Minimal per-turn runtime surface exposed to tool handlers.
 *
 * <p>tools cannot depend on {@code RuntimeState}; the loop adapts the current state into this
 * interface at the execution boundary.
 */
public interface ToolRuntimeContext {

    Map<String, Object> metadata();

    default Object metadataOrDefault(String key, Object defaultValue) {
        Object value = metadata().get(key);
        return value == null ? defaultValue : value;
    }

    void putMetadata(String key, Object value);

    static ToolRuntimeContext unavailable() {
        return UnavailableToolRuntimeContext.INSTANCE;
    }
}

final class UnavailableToolRuntimeContext implements ToolRuntimeContext {
    static final UnavailableToolRuntimeContext INSTANCE = new UnavailableToolRuntimeContext();

    private UnavailableToolRuntimeContext() {
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of();
    }

    @Override
    public void putMetadata(String key, Object value) {
        throw new IllegalStateException("Tool runtime context is not available");
    }
}
