package com.pixflow.app.ai;

import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import com.pixflow.infra.ai.spi.ModelQuotaLimiter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AiAdmissionConfiguration {
    @Bean
    GlobalConcurrencyLimiter globalConcurrencyLimiter(
            DistributedSemaphore semaphore,
            CacheNamespace namespace) {
        return new RedisAiConcurrencyLimiter(semaphore, namespace);
    }

    @Bean
    ModelQuotaLimiter modelQuotaLimiter(
            DistributedTokenBucket tokenBucket,
            CacheNamespace namespace,
            AiProperties properties) {
        return new RedisModelQuotaLimiter(tokenBucket, namespace, properties);
    }
}
