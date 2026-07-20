package com.pixflow.infra.cache.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * infra/cache 内部异常，跨边界后由 common 归一化为 DEPENDENCY。
 */
public class CacheException extends RuntimeException {
    private final CacheErrorCode code;

    private final String operation;

    private final String keyNamespace;

    private final boolean retryable;

    private final Map<String, Object> details;

    public CacheException(CacheErrorCode code, String operation, String keyNamespace, String message) {
        this(code, operation, keyNamespace, message, null, true, Map.of());
    }

    public CacheException(CacheErrorCode code, String operation, String keyNamespace, String message, Throwable cause) {
        this(code, operation, keyNamespace, message, cause, true, Map.of());
    }

    public CacheException(
            CacheErrorCode code,
            String operation,
            String keyNamespace,
            String message,
            Throwable cause,
            boolean retryable,
            Map<String, ?> details) {
        super(message, cause);
        this.code = code;
        this.operation = operation;
        this.keyNamespace = keyNamespace;
        this.retryable = retryable;
        this.details = immutableCopy(details);
    }

    public CacheErrorCode code() {
        return code;
    }

    public String operation() {
        return operation;
    }

    public String keyNamespace() {
        return keyNamespace;
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
