package com.pixflow.infra.thirdparty.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.Objects;
import java.util.function.Supplier;

public final class ThirdPartyCallTemplate {
    private final DistributedSemaphore distributedSemaphore;
    private final CacheNamespace cacheNamespace;
    private final ThirdPartyResilienceRegistry resilienceRegistry;
    private final ThirdPartyErrorMapper errorMapper;

    public ThirdPartyCallTemplate(
            DistributedSemaphore distributedSemaphore,
            CacheNamespace cacheNamespace,
            ThirdPartyResilienceRegistry resilienceRegistry,
            ThirdPartyErrorMapper errorMapper) {
        this.distributedSemaphore = Objects.requireNonNull(distributedSemaphore, "distributedSemaphore");
        this.cacheNamespace = Objects.requireNonNull(cacheNamespace, "cacheNamespace");
        this.resilienceRegistry = Objects.requireNonNull(resilienceRegistry, "resilienceRegistry");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper");
    }

    public <T> T execute(ThirdPartyCallContext context, Supplier<T> action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        CacheKey key = cacheNamespace.key("sem", "thirdparty", context.api());
        // 先拿全局信号量，再进入本实例韧性装饰，避免重试期间并发超出第三方总上限。
        try (DistributedSemaphore.Permit ignored = distributedSemaphore.acquire(key, 1, context.semaphoreWaitTime())) {
            ThirdPartyResilienceRegistry.ResilienceSet set = resilienceRegistry.get(context.providerId());
            Supplier<T> decorated = Retry.decorateSupplier(
                    set.retry(),
                    CircuitBreaker.decorateSupplier(
                            set.circuitBreaker(),
                            Bulkhead.decorateSupplier(
                                    set.bulkhead(),
                                    RateLimiter.decorateSupplier(set.rateLimiter(), action))));
            return decorated.get();
        } catch (PixFlowException ex) {
            throw ex;
        } catch (CallNotPermittedException ex) {
            throw errorMapper.map(ex).withRecoveryOverride(RecoveryHint.SKIP);
        } catch (RuntimeException ex) {
            throw errorMapper.map(ex);
        }
    }
}
