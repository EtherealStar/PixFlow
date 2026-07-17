package com.pixflow.infra.thirdparty.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class ThirdPartyCallTemplate {
    private final DistributedSemaphore distributedSemaphore;
    private final DistributedTokenBucket distributedTokenBucket;
    private final CacheNamespace cacheNamespace;
    private final ThirdPartyResilienceRegistry resilienceRegistry;
    private final ThirdPartyErrorMapper errorMapper;

    private final ThirdPartyProperties properties;

    private final ThirdPartyMetrics metrics;

    public ThirdPartyCallTemplate(
            DistributedSemaphore distributedSemaphore,
            DistributedTokenBucket distributedTokenBucket,
            CacheNamespace cacheNamespace,
            ThirdPartyResilienceRegistry resilienceRegistry,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyProperties properties,
            ThirdPartyMetrics metrics) {
        this.distributedSemaphore = Objects.requireNonNull(distributedSemaphore, "distributedSemaphore");
        this.distributedTokenBucket = Objects.requireNonNull(distributedTokenBucket, "distributedTokenBucket");
        this.cacheNamespace = Objects.requireNonNull(cacheNamespace, "cacheNamespace");
        this.resilienceRegistry = Objects.requireNonNull(resilienceRegistry, "resilienceRegistry");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public <T> T execute(ThirdPartyCallContext context, Supplier<T> action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        try {
            ThirdPartyResilienceRegistry.ResilienceSet set = resilienceRegistry.get(context.providerId());
            // Retry 每次调用 attempt 都会重新获取本机、集群并发许可并消费额度。
            Supplier<T> attempt = () -> executeAttempt(context, action);
            Supplier<T> decorated = Retry.decorateSupplier(
                    set.retry(),
                    CircuitBreaker.decorateSupplier(
                            set.circuitBreaker(),
                            Bulkhead.decorateSupplier(set.bulkhead(), attempt)));
            return decorated.get();
        } catch (PixFlowException ex) {
            throw ex;
        } catch (CallNotPermittedException ex) {
            throw errorMapper.map(ex).withRecoveryOverride(RecoveryHint.SKIP);
        } catch (RuntimeException ex) {
            throw errorMapper.map(ex);
        }
    }

    private <T> T executeAttempt(ThirdPartyCallContext context, Supplier<T> action) {
        ThirdPartyProperties.OutboundQuota quota;
        try {
            quota = properties.outboundQuota(context.providerId(), context.api());
        } catch (IllegalStateException ex) {
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_NOT_CONFIGURED,
                    ex.getMessage(),
                    ex,
                    Map.of(),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        CacheKey semaphoreKey = cacheNamespace.key("sem", "thirdparty", context.providerId(), context.api());
        CacheKey bucketKey = cacheNamespace.key("bucket", "thirdparty", context.providerId(), context.api());
        try (DistributedSemaphore.Permit ignored = distributedSemaphore.acquire(
                semaphoreKey, 1, context.semaphoreWaitTime())) {
            TokenBucketDecision decision;
            try {
                decision = distributedTokenBucket.tryConsume(
                        bucketKey,
                        new TokenBucketPolicy(
                                quota.capacity(),
                                quota.refillTokens(),
                                quota.refillPeriod(),
                                quota.idleTtl()),
                        quota.costPerAttempt());
            } catch (CacheException ex) {
                metrics.recordQuota(context.providerId(), context.api(), ThirdPartyMetrics.QuotaResult.ERROR);
                throw new PixFlowException(
                        ThirdPartyErrorCode.THIRDPARTY_QUOTA_UNAVAILABLE,
                        "第三方出站额度服务不可用",
                        ex,
                        Map.of(),
                        RecoveryHint.RETRY,
                        null,
                        null);
            }
            if (!decision.allowed()) {
                metrics.recordQuota(context.providerId(), context.api(), ThirdPartyMetrics.QuotaResult.REJECTED);
                throw new PixFlowException(
                        ThirdPartyErrorCode.THIRDPARTY_LOCAL_RATE_LIMITED,
                        "第三方出站额度暂时不足",
                        null,
                        Map.of(),
                        RecoveryHint.RETRY,
                        decision.retryAfter(),
                        null);
            }
            metrics.recordQuota(context.providerId(), context.api(), ThirdPartyMetrics.QuotaResult.ALLOWED);
            return action.get();
        }
    }
}
