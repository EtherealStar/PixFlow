package com.pixflow.infra.thirdparty.error;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.common.sanitize.Sanitizer;
import java.net.SocketTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

public final class ThirdPartyErrorMapper {
    public PixFlowException map(Throwable error) {
        if (error instanceof PixFlowException pixFlowException) {
            return pixFlowException;
        }
        if (error instanceof RestClientResponseException responseException) {
            return fromStatus(responseException.getStatusCode(), responseException.getResponseHeaders(), responseException.getResponseBodyAsString(), responseException);
        }
        if (error instanceof CallNotPermittedException) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_CIRCUIT_OPEN, "circuit open", error, RecoveryHint.SKIP, null);
        }
        if (error instanceof RequestNotPermitted) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_RATE_LIMITED, "rate limiter rejected", error, RecoveryHint.RETRY, null);
        }
        if (error instanceof BulkheadFullException) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR, "bulkhead full", error, RecoveryHint.RETRY, null);
        }
        if (error instanceof SocketTimeoutException) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT, "socket timeout", error, RecoveryHint.RETRY, null);
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT, "timeout", error, RecoveryHint.RETRY, null);
        }
        if (error instanceof RestClientException) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_NETWORK_ERROR, "http client error", error, RecoveryHint.RETRY, null);
        }
        return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR, "thirdparty failure", error, RecoveryHint.RETRY, null);
    }

    public PixFlowException fromStatus(HttpStatusCode status, HttpHeaders headers, String message, Throwable cause) {
        int code = status.value();
        if (code == 429) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_RATE_LIMITED, message, cause, RecoveryHint.RETRY, retryAfter(headers));
        }
        if (code == 401 || code == 403) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_AUTH_ERROR, message, cause, RecoveryHint.TERMINATE, null);
        }
        if (code == 501 || code == 505) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR, message, cause, RecoveryHint.TERMINATE, null);
        }
        if (code >= 500) {
            return exception(ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR, message, cause, RecoveryHint.RETRY, null);
        }
        return exception(ThirdPartyErrorCode.THIRDPARTY_INVALID_REQUEST, message, cause, RecoveryHint.TERMINATE, null);
    }

    public PixFlowException invalidResponse(String message, Throwable cause) {
        return exception(ThirdPartyErrorCode.THIRDPARTY_RESPONSE_INVALID, message, cause, RecoveryHint.RETRY, null);
    }

    private static PixFlowException exception(
            ThirdPartyErrorCode code,
            String message,
            Throwable cause,
            RecoveryHint recoveryHint,
            Duration retryAfter) {
        String safeMessage = Sanitizer.sanitizeMessage(message);
        return new PixFlowException(code, safeMessage, cause, Map.of(), recoveryHint, retryAfter, null);
    }

    private static Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            try {
                Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed, Instant::from);
                Duration duration = Duration.between(Instant.now(), retryAt);
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (DateTimeException ignored) {
                return null;
            }
        }
    }
}
