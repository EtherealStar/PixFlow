package com.pixflow.infra.thirdparty.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
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
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(
                semaphore, new AllowingTokenBucket(), namespace, registry, new ThirdPartyErrorMapper(), properties);

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
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(
                semaphore, tokenBucket, namespace, new ThirdPartyResilienceRegistry(properties), new ThirdPartyErrorMapper(), properties);
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
