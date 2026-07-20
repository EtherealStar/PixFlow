package com.pixflow.infra.ai.error;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;

/**
 * 把 provider / HTTP 异常收口成 PixFlowException。
 */
public final class ProviderErrorMapper {
    public ProviderErrorMapper() {
    }

    public static PixFlowException fromHttpStatus(HttpStatusCode status, Throwable cause, HttpHeaders headers,
            String message) {
        int code = status.value();
        if (code == 429) {
            return exception(AiErrorCode.MODEL_RATE_LIMITED, message, cause, RecoveryHint.RETRY, retryAfter(headers));
        }
        if (code == 413 || containsContextLimit(message)) {
            return exception(AiErrorCode.MODEL_CONTEXT_LIMIT, message, cause, RecoveryHint.COMPACT, null);
        }
        if (code == 401 || code == 403) {
            return exception(AiErrorCode.MODEL_AUTH_ERROR, message, cause, RecoveryHint.TERMINATE, null);
        }
        if (code >= 500) {
            return exception(AiErrorCode.MODEL_PROVIDER_ERROR, message, cause, RecoveryHint.RETRY, null);
        }
        return exception(AiErrorCode.MODEL_PROVIDER_ERROR, message, cause, RecoveryHint.TERMINATE, null);
    }

    public static PixFlowException fromMessage(String message, Throwable cause) {
        if (containsContextLimit(message)) {
            return exception(AiErrorCode.MODEL_CONTEXT_LIMIT, message, cause, RecoveryHint.COMPACT, null);
        }
        if (containsRateLimit(message)) {
            return exception(AiErrorCode.MODEL_RATE_LIMITED, message, cause, RecoveryHint.RETRY, null);
        }
        if (containsAuth(message)) {
            return exception(AiErrorCode.MODEL_AUTH_ERROR, message, cause, RecoveryHint.TERMINATE, null);
        }
        return exception(AiErrorCode.MODEL_PROVIDER_ERROR, message, cause, RecoveryHint.RETRY, null);
    }

    private static PixFlowException exception(
            AiErrorCode code,
            String message,
            Throwable cause,
            RecoveryHint recoveryHint,
            Duration retryAfter) {
        return new PixFlowException(code, sanitize(message), cause, Map.of(), recoveryHint, retryAfter, null);
    }

    private static String sanitize(String message) {
        return message == null ? null : message.trim();
    }

    private static boolean containsContextLimit(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context limit") || lower.contains("context_length_exceeded")
                || lower.contains("token limit");
    }

    private static boolean containsRateLimit(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("rate limit") || lower.contains("too many requests");
    }

    private static boolean containsAuth(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unauthorized") || lower.contains("forbidden") || lower.contains("api key");
    }

    private static Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
