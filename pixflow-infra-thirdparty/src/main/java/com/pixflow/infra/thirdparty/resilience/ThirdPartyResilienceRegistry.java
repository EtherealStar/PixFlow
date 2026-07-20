package com.pixflow.infra.thirdparty.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.cache.error.CacheException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.RestClientException;

public final class ThirdPartyResilienceRegistry {
    private final ThirdPartyProperties properties;

    private final ConcurrentMap<String, ResilienceSet> cache = new ConcurrentHashMap<>();

    public ThirdPartyResilienceRegistry(ThirdPartyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public ResilienceSet get(String providerId) {
        return cache.computeIfAbsent(providerId, this::create);
    }

    private ResilienceSet create(String providerId) {
        ThirdPartyProperties.Provider provider = properties.providers().get(providerId);
        ThirdPartyProperties.Resilience base = properties.resilience();
        ThirdPartyProperties.ResilienceOverride override = provider == null ? null : provider.resilienceOverride();
        int maxAttempts = override != null && override.maxAttempts() != null
                ? override.maxAttempts()
                : base.maxAttempts();
        Duration baseDelay = override != null && override.baseDelay() != null ? override.baseDelay() : base.baseDelay();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalBiFunction((attempt, outcome) -> retryDelayMillis(
                        attempt, outcome.isLeft() ? outcome.getLeft() : null, baseDelay, base.maxDelay()))
                // provider 已在源头把 429/5xx/超时等翻成 PixFlowException，重试只看控制流提示。
                .retryOnException(ThirdPartyResilienceRegistry::isRetryable)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .ignoreException(ThirdPartyResilienceRegistry::isAdmissionRejection)
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(base.bulkheadMaxConcurrent())
                .build();
        return new ResilienceSet(
                Retry.of(providerId, retryConfig),
                CircuitBreaker.of(providerId, circuitBreakerConfig),
                Bulkhead.of(providerId, bulkheadConfig));
    }

    public record ResilienceSet(Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
    }

    private static boolean isRetryable(Throwable throwable) {
        if (throwable instanceof PixFlowException exception) {
            return exception.recovery() == RecoveryHint.RETRY;
        }
        return throwable instanceof RestClientException
                || throwable instanceof TimeoutException
                || throwable instanceof SocketTimeoutException;
    }

    private static boolean isAdmissionRejection(Throwable throwable) {
        if (throwable instanceof BulkheadFullException || throwable instanceof CacheException) {
            return true;
        }
        if (throwable instanceof PixFlowException exception) {
            return exception.code() == com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode.THIRDPARTY_RATE_LIMITED
                    || exception.code() == com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode
                            .THIRDPARTY_LOCAL_RATE_LIMITED
                    || exception.code() == com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode
                            .THIRDPARTY_QUOTA_UNAVAILABLE;
        }
        return false;
    }

    private static long retryDelayMillis(
            int attempt,
            Throwable throwable,
            Duration baseDelay,
            Duration maxDelay) {
        long exponent = Math.min(Math.max(0, attempt - 1), 30);
        long multiplier = 1L << exponent;
        long backoff;
        try {
            backoff = Math.multiplyExact(baseDelay.toMillis(), multiplier);
        } catch (ArithmeticException ex) {
            backoff = Long.MAX_VALUE;
        }
        long configured = Math.min(backoff, maxDelay.toMillis());
        if (throwable instanceof PixFlowException exception && exception.retryAfter() != null) {
            return Math.max(configured, exception.retryAfter().toMillis());
        }
        return configured;
    }
}
