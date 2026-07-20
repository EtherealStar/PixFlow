package com.pixflow.app.ai;

import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.ModelQuotaLimiter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import java.util.Objects;

final class RedisModelQuotaLimiter implements ModelQuotaLimiter {
    private final DistributedTokenBucket tokenBucket;

    private final CacheNamespace namespace;

    private final AiProperties properties;

    RedisModelQuotaLimiter(
            DistributedTokenBucket tokenBucket,
            CacheNamespace namespace,
            AiProperties properties) {
        this.tokenBucket = Objects.requireNonNull(tokenBucket, "tokenBucket");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public QuotaDecision tryConsume(ModelRole role, String provider, String quotaGroup, long cost) {
        AiProperties.Quota quota = properties.quota(role);
        TokenBucketDecision decision = tokenBucket.tryConsume(
                namespace.key("bucket", "ai", provider, quotaGroup),
                new TokenBucketPolicy(
                        quota.capacity(),
                        quota.refillTokens(),
                        quota.refillPeriod(),
                        quota.idleTtl()),
                cost);
        return new QuotaDecision(decision.allowed(), decision.remaining(), decision.retryAfter());
    }
}
