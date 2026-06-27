package com.pixflow.infra.thirdparty.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

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
        int maxAttempts = override != null && override.maxAttempts() != null ? override.maxAttempts() : base.maxAttempts();
        Duration timeout = override != null && override.timeout() != null ? override.timeout() : base.timeout();
        Duration baseDelay = override != null && override.baseDelay() != null ? override.baseDelay() : base.baseDelay();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(baseDelay)
                .ignoreExceptions(PixFlowException.class, IllegalArgumentException.class)
                .retryOnException(throwable -> !(throwable instanceof PixFlowException)
                        && !(throwable instanceof IllegalArgumentException))
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(base.bulkheadMaxConcurrent())
                .build();
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(base.rateLimitLimitForPeriod())
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(timeout)
                .build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .build();
        return new ResilienceSet(
                Retry.of(providerId, retryConfig),
                CircuitBreaker.of(providerId, circuitBreakerConfig),
                Bulkhead.of(providerId, bulkheadConfig),
                RateLimiter.of(providerId, rateLimiterConfig),
                TimeLimiter.of(providerId, timeLimiterConfig));
    }

    public record ResilienceSet(Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead, RateLimiter rateLimiter, TimeLimiter timeLimiter) {
    }
}
