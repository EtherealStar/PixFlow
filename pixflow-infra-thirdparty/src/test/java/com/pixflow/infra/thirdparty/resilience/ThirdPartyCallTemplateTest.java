package com.pixflow.infra.thirdparty.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
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
        ThirdPartyResilienceRegistry registry = new ThirdPartyResilienceRegistry(new ThirdPartyProperties(null, Map.of(), null, null));
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(semaphore, namespace, registry, new ThirdPartyErrorMapper());

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
        ThirdPartyProperties properties = new ThirdPartyProperties(null, Map.of(), null,
                new ThirdPartyProperties.Resilience(2, Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofSeconds(1), 8, 8));
        ThirdPartyCallTemplate template = new ThirdPartyCallTemplate(semaphore, namespace, new ThirdPartyResilienceRegistry(properties), new ThirdPartyErrorMapper());
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
        assertThat(semaphore.acquired.get()).isEqualTo(1);
        assertThat(semaphore.released.get()).isEqualTo(1);
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
}
