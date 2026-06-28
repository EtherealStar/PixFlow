package com.pixflow.infra.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * infra/vector 内部异常，跨模块边界后由 common 归一化为 DEPENDENCY。
 */
public class VectorException extends RuntimeException {
    private final String operation;
    private final String collection;
    private final boolean retryable;
    private final Map<String, Object> details;

    public VectorException(String operation, String collection, boolean retryable, String message) {
        this(operation, collection, retryable, message, null, Map.of());
    }

    public VectorException(String operation, String collection, boolean retryable, String message, Throwable cause) {
        this(operation, collection, retryable, message, cause, Map.of());
    }

    public VectorException(
            String operation,
            String collection,
            boolean retryable,
            String message,
            Throwable cause,
            Map<String, ?> details) {
        super(message, cause);
        this.operation = operation;
        this.collection = collection;
        this.retryable = retryable;
        this.details = immutableCopy(details);
    }

    public String operation() {
        return operation;
    }

    public String collection() {
        return collection;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
