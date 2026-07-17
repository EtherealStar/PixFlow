package com.pixflow.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisModelQuotaLimiterTest {
    @Test
    void sameProviderAndQuotaGroupShareKeyWhileDifferentGroupsAreIsolated() {
        RecordingTokenBucket bucket = new RecordingTokenBucket();
        RedisModelQuotaLimiter limiter = new RedisModelQuotaLimiter(
                bucket,
                new DefaultCacheNamespace("test", Duration.ofMinutes(1)),
                properties());

        limiter.tryConsume(ModelRole.PRIMARY_CHAT, "custom", "shared", 1);
        limiter.tryConsume(ModelRole.VISION, "custom", "shared", 1);
        limiter.tryConsume(ModelRole.VISION, "custom", "vision-only", 1);

        assertThat(bucket.keys.get(0)).isEqualTo(bucket.keys.get(1));
        assertThat(bucket.keys.get(2)).isNotEqualTo(bucket.keys.get(0));
        assertThat(bucket.keys).allMatch(key -> key.startsWith("pixflow:test:bucket:ai:custom:"));
    }

    private static AiProperties properties() {
        AiProperties.Quota quota = new AiProperties.Quota(
                "shared", 10, 10, Duration.ofSeconds(1), Duration.ofMinutes(1), 1);
        return new AiProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiProperties.QuotaSettings(Map.of(
                        ModelRole.PRIMARY_CHAT, quota,
                        ModelRole.VISION, quota)));
    }

    private static final class RecordingTokenBucket implements DistributedTokenBucket {
        private final List<String> keys = new ArrayList<>();

        @Override
        public TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost) {
            keys.add(key.value());
            return new TokenBucketDecision(true, policy.capacity() - cost, Duration.ZERO);
        }
    }
}
