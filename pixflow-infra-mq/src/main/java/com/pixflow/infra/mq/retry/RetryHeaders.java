package com.pixflow.infra.mq.retry;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RetryHeaders {
    public static final String RETRY_COUNT = "x-retry-count";
    public static final String ORIGINAL_TOPIC = "x-original-topic";
    public static final String ORIGINAL_TAG = "x-original-tag";
    public static final String FIRST_FAILURE_AT = "x-first-failure-at";
    public static final String LAST_ERROR_CODE = "x-last-error-code";
    public static final String LAST_ERROR_MESSAGE = "x-last-error-message";
    public static final String TRACE_ID = "x-trace-id";

    private RetryHeaders() {}

    public static int retryCount(Map<String, Object> headers) {
        Object raw = headers == null ? null : headers.get(RETRY_COUNT);
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String value) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    public static Map<String, Object> incrementRetry(Map<String, Object> headers) {
        Map<String, Object> copy = copy(headers);
        copy.put(RETRY_COUNT, retryCount(copy) + 1);
        copy.putIfAbsent(FIRST_FAILURE_AT, Instant.now().toString());
        return copy;
    }

    public static Map<String, Object> withOriginalDestination(Map<String, Object> headers, String topic, String tag) {
        Map<String, Object> copy = copy(headers);
        copy.putIfAbsent(ORIGINAL_TOPIC, topic);
        copy.putIfAbsent(ORIGINAL_TAG, tag);
        return copy;
    }

    public static Map<String, Object> withFailure(Map<String, Object> headers, PixFlowException error) {
        Map<String, Object> copy = copy(headers);
        copy.put(LAST_ERROR_CODE, error.code().code());
        copy.put(LAST_ERROR_MESSAGE, Sanitizer.sanitizeMessage(error.getMessage()));
        copy.putIfAbsent(FIRST_FAILURE_AT, Instant.now().toString());
        return copy;
    }

    public static Map<String, Object> withFailure(Map<String, Object> headers, String errorCode, String message) {
        Map<String, Object> copy = copy(headers);
        copy.put(LAST_ERROR_CODE, errorCode);
        copy.put(LAST_ERROR_MESSAGE, Sanitizer.sanitizeMessage(message));
        copy.putIfAbsent(FIRST_FAILURE_AT, Instant.now().toString());
        return copy;
    }

    private static Map<String, Object> copy(Map<String, Object> headers) {
        return headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }
}
