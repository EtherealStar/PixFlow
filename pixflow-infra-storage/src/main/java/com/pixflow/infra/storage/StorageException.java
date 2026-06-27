package com.pixflow.infra.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * storage 内部异常，跨边界后再由 common 归一化。
 */
public class StorageException extends RuntimeException {
    private final String operation;
    private final BucketType bucket;
    private final String key;
    private final boolean retryable;
    private final Map<String, Object> details;

    public StorageException(String operation, BucketType bucket, String key, boolean retryable, String message, Throwable cause) {
        this(operation, bucket, key, retryable, message, cause, Map.of());
    }

    public StorageException(
            String operation,
            BucketType bucket,
            String key,
            boolean retryable,
            String message,
            Throwable cause,
            Map<String, ?> details) {
        super(message, cause);
        this.operation = operation;
        this.bucket = bucket;
        this.key = key;
        this.retryable = retryable;
        this.details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String operation() {
        return operation;
    }

    public BucketType bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
