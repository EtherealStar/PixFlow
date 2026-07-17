package com.pixflow.common.error;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误载体：业务层、基础设施层和出口层都只和它打交道。
 */
public class PixFlowException extends RuntimeException {
    private final ErrorCode code;

    private final Map<String, Object> details;

    private final RecoveryHint recoveryOverride;

    private final Duration retryAfter;

    private final String traceId;

    public PixFlowException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public PixFlowException(ErrorCode code, String message, Throwable cause) {
        this(code, message, cause, Collections.emptyMap(), null, null, null);
    }

    public PixFlowException(ErrorCode code, String message, Throwable cause, Map<String, ?> details) {
        this(code, message, cause, details, null, null, null);
    }

    public PixFlowException(
            ErrorCode code,
            String message,
            Throwable cause,
            Map<String, ?> details,
            RecoveryHint recoveryOverride,
            Duration retryAfter,
            String traceId) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.details = immutableCopy(details);
        this.recoveryOverride = recoveryOverride;
        this.retryAfter = retryAfter;
        this.traceId = traceId;
    }

    public ErrorCode code() {
        return code;
    }

    public ErrorCategory category() {
        return code.category();
    }

    public RecoveryHint recovery() {
        return recoveryOverride != null ? recoveryOverride : category().defaultRecovery();
    }

    public Map<String, Object> details() {
        return details;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public String traceId() {
        return traceId;
    }

    public PixFlowException withTraceId(String newTraceId) {
        return new PixFlowException(code, getMessage(), getCause(), details, recoveryOverride, retryAfter, newTraceId);
    }

    public PixFlowException withRecoveryOverride(RecoveryHint newRecovery) {
        return new PixFlowException(code, getMessage(), getCause(), details, newRecovery, retryAfter, traceId);
    }

    public PixFlowException withDetails(Map<String, ?> newDetails) {
        return new PixFlowException(code, getMessage(), getCause(), newDetails, recoveryOverride, retryAfter, traceId);
    }

    public PixFlowException withRetryAfter(Duration newRetryAfter) {
        return new PixFlowException(code, getMessage(), getCause(), details, recoveryOverride, newRetryAfter, traceId);
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
