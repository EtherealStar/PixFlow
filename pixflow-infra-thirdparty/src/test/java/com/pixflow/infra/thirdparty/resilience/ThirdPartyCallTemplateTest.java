package com.pixflow.infra.thirdparty.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThirdPartyCallTemplateTest {

    @Test
    void releasesPermitOnSuccessAndFailure() {
        RecordingSemaphore semaphore = new RecordingSemaphore();
        CacheNamespace namespace = new com.pixflow.infra.cache.key.DefaultCacheNamespace("dev", Duration.ofMinutes(1));
        ThirdPartyProperties properties = properties(3);
        ThirdPartyResilienceRegistry registry = new ThirdPartyResilienceRegistry(properties);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(
                semaphore, new AllowingTokenBucket(), namespace, registry, new ThirdPartyErrorMapper(), properties,
                new ThirdPartyMetrics(meters));

        String value = template.execute(new ThirdPartyCallContext("bg-removal", "p1", Duration.ofMillis(10)), () -> "ok");
        assertThat(value).isEqualTo("ok");
        assertThat(semaphore.acquired.get()).isEqualTo(1);
        assertThat(semaphore.released.get()).isEqualTo(1);

        assertThatThrownBy(() -> template.execute(new ThirdPartyCallContext("bg-removal", "p1", Duration.ofMillis(10)), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(com.pixflow.common.error.PixFlowException.class);
        assertThat(semaphore.released.get()).isEqualTo(2);
    }

    @Test
    void retriesNormalizedRetryablePixFlowException() {
        RecordingSemaphore semaphore = new RecordingSemaphore();
        CacheNamespace namespace = new com.pixflow.infra.cache.key.DefaultCacheNamespace("dev", Duration.ofMinutes(1));
        ThirdPartyProperties properties = properties(2);
        RecordingTokenBucket tokenBucket = new RecordingTokenBucket();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(
                semaphore, tokenBucket, namespace, new ThirdPartyResilienceRegistry(properties),
                new ThirdPartyErrorMapper(), properties, new ThirdPartyMetrics(meters));
        AtomicInteger attempts = new AtomicInteger();

        String value = template.execute(new ThirdPartyCallContext("bg-removal", "p-retry", Duration.ofMillis(10)), () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new com.pixflow.common.error.PixFlowException(
                        ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR,
                        "temporary provider failure",
                        null,
                        Map.of(),
                        com.pixflow.common.error.RecoveryHint.RETRY,
                        null,
                        null);
            }
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(semaphore.acquired.get()).isEqualTo(2);
        assertThat(semaphore.released.get()).isEqualTo(2);
        assertThat(tokenBucket.consumed.get()).isEqualTo(2);
        assertThat(meters.get("pixflow.thirdparty.quota")
                .tags("provider", "p-retry", "api", "bg-removal", "result", "allowed")
                .counter().count()).isEqualTo(2.0d);
    }

    @Test
    void quotaRejectionAndFailureAreDistinctAndDoNotReachProvider() {
        ThirdPartyProperties properties = properties(1);
        CacheNamespace namespace = new com.pixflow.infra.cache.key.DefaultCacheNamespace(
                "dev", Duration.ofMinutes(1));
        SimpleMeterRegistry rejectedMeters = new SimpleMeterRegistry();
        AtomicInteger rejectedCalls = new AtomicInteger();
        ThirdPartyResilienceRegistry rejectedRegistry = new ThirdPartyResilienceRegistry(properties);
        ThirdPartyCallTemplate rejected = new ThirdPartyCallTemplate(
                new RecordingSemaphore(),
                (key, policy, cost) -> new TokenBucketDecision(false, 0, Duration.ofSeconds(3)),
                namespace,
                rejectedRegistry,
                new ThirdPartyErrorMapper(),
                properties,
                new ThirdPartyMetrics(rejectedMeters));

        assertThatThrownBy(() -> rejected.execute(
                new ThirdPartyCallContext("bg-removal", "p1", Duration.ofMillis(10)),
                () -> {
                    rejectedCalls.incrementAndGet();
                    return "unexpected";
                }))
                .isInstanceOfSatisfying(com.pixflow.common.error.PixFlowException.class,
                        error -> {
                            assertThat(error.code())
                                    .isEqualTo(ThirdPartyErrorCode.THIRDPARTY_LOCAL_RATE_LIMITED);
                            assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(3));
                        });
        assertThat(rejectedCalls).hasValue(0);
        assertThat(rejectedMeters.get("pixflow.thirdparty.quota")
                .tags("provider", "p1", "api", "bg-removal", "result", "rejected")
                .counter().count()).isEqualTo(1.0d);
        assertThat(rejectedRegistry.get("p1").circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();

        SimpleMeterRegistry errorMeters = new SimpleMeterRegistry();
        AtomicInteger errorCalls = new AtomicInteger();
        ThirdPartyResilienceRegistry unavailableRegistry = new ThirdPartyResilienceRegistry(properties);
        ThirdPartyCallTemplate unavailable = new ThirdPartyCallTemplate(
                new RecordingSemaphore(),
                (key, policy, cost) -> {
                    throw new CacheException(CacheErrorCode.CACHE_TOKEN_BUCKET_FAILED,
                            "consume", "bucket", "redis unavailable");
                },
                namespace,
                unavailableRegistry,
                new ThirdPartyErrorMapper(),
                properties,
                new ThirdPartyMetrics(errorMeters));

        assertThatThrownBy(() -> unavailable.execute(
                new ThirdPartyCallContext("bg-removal", "p1", Duration.ofMillis(10)),
                () -> {
                    errorCalls.incrementAndGet();
                    return "unexpected";
                }))
                .isInstanceOfSatisfying(com.pixflow.common.error.PixFlowException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(ThirdPartyErrorCode.THIRDPARTY_QUOTA_UNAVAILABLE));
        assertThat(errorCalls).hasValue(0);
        assertThat(errorMeters.get("pixflow.thirdparty.quota")
                .tags("provider", "p1", "api", "bg-removal", "result", "error")
                .counter().count()).isEqualTo(1.0d);
        assertThat(unavailableRegistry.get("p1").circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void providerRateLimitIsIgnoredButProviderFailureCountsForCircuitBreaker() {
        ThirdPartyProperties properties = properties(1);
        ThirdPartyResilienceRegistry registry = new ThirdPartyResilienceRegistry(properties);
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(
                new RecordingSemaphore(),
                new AllowingTokenBucket(),
                new com.pixflow.infra.cache.key.DefaultCacheNamespace("dev", Duration.ofMinutes(1)),
                registry,
                new ThirdPartyErrorMapper(),
                properties,
                new ThirdPartyMetrics(new SimpleMeterRegistry()));
        ThirdPartyCallContext context = new ThirdPartyCallContext("bg-removal", "p1", Duration.ofMillis(10));

        assertThatThrownBy(() -> template.execute(context, () -> {
            throw new com.pixflow.common.error.PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_RATE_LIMITED,
                    "provider rate limited",
                    null,
                    Map.of(),
                    com.pixflow.common.error.RecoveryHint.RETRY,
                    Duration.ofSeconds(1),
                    null);
        })).isInstanceOf(com.pixflow.common.error.PixFlowException.class);
        assertThat(registry.get("p1").circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();

        assertThatThrownBy(() -> template.execute(context, () -> {
            throw new com.pixflow.common.error.PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR,
                    "provider failed",
                    null,
                    Map.of(),
                    com.pixflow.common.error.RecoveryHint.RETRY,
                    null,
                    null);
        })).isInstanceOf(com.pixflow.common.error.PixFlowException.class);
        assertThat(registry.get("p1").circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private static ThirdPartyProperties properties(int maxAttempts) {
        var quota = new ThirdPartyProperties.OutboundQuota(
                10, 10, Duration.ofSeconds(1), Duration.ofMinutes(1), 1);
        return new ThirdPartyProperties(
                null,
                Map.of(),
                null,
                new ThirdPartyProperties.Resilience(
                        maxAttempts, Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofSeconds(1), 8),
                Map.of("p1", Map.of("bg-removal", quota), "p-retry", Map.of("bg-removal", quota)));
    }

    static final class RecordingSemaphore implements DistributedSemaphore {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        @Override
        public Permit acquire(CacheKey key, int permits, Duration waitTime) {
            acquired.incrementAndGet();
            return () -> released.incrementAndGet();
        }
    }

    static class AllowingTokenBucket implements DistributedTokenBucket {
        @Override
        public TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost) {
            return new TokenBucketDecision(true, policy.capacity() - cost, Duration.ZERO);
        }
    }

    static final class RecordingTokenBucket extends AllowingTokenBucket {
        final AtomicInteger consumed = new AtomicInteger();

        @Override
        public TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost) {
            consumed.incrementAndGet();
            return super.tryConsume(key, policy, cost);
        }
    }
}
