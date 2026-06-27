package com.pixflow.infra.cache.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pixflow.contracts.confirmation.ConfirmationTokenStore;
import com.pixflow.infra.cache.confirmation.RedisConfirmationTokenStore;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.counter.RedissonAtomicCounter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.cache.lock.RedissonLockTemplate;
import com.pixflow.infra.cache.observability.CacheMetrics;
import com.pixflow.infra.cache.observability.MicrometerCacheMetrics;
import com.pixflow.infra.cache.observability.NoopCacheMetrics;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.semaphore.RedissonDistributedSemaphore;
import com.pixflow.infra.cache.store.CacheStore;
import com.pixflow.infra.cache.store.RedissonCacheStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return defaultObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(CacheProperties properties, ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(CacheAutoConfiguration::defaultObjectMapper);
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec(objectMapper));
        config.setLockWatchdogTimeout(properties.getLock().getWatchdogTimeout().toMillis());
        if (properties.getMode() != CacheProperties.Mode.SINGLE) {
            throw new IllegalArgumentException("当前版本仅联调 single Redis，已预留 sentinel/cluster 配置位");
        }
        config.useSingleServer()
                .setAddress(properties.getAddress())
                .setPassword(StringUtils.hasText(properties.getPassword()) ? properties.getPassword() : null)
                .setConnectionPoolSize(properties.getPool().getSize())
                .setConnectionMinimumIdleSize(properties.getPool().getMinIdle())
                .setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(properties.getTimeout().toMillis()))
                .setRetryAttempts(properties.getRetryAttempts())
                .setRetryInterval(Math.toIntExact(properties.getRetryInterval().toMillis()));
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheNamespace cacheNamespace(CacheProperties properties) {
        return new DefaultCacheNamespace(properties.getEnvPrefix(), properties.getDefaultTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheMetrics cacheMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry != null ? new MicrometerCacheMetrics(meterRegistry) : new NoopCacheMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheStore cacheStore(RedissonClient redissonClient, ObjectMapper objectMapper, CacheMetrics metrics) {
        return new RedissonCacheStore(redissonClient, objectMapper, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public AtomicCounter atomicCounter(RedissonClient redissonClient) {
        return new RedissonAtomicCounter(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockTemplate lockTemplate(RedissonClient redissonClient, CacheMetrics metrics) {
        return new RedissonLockTemplate(redissonClient, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedSemaphore distributedSemaphore(
            RedissonClient redissonClient,
            CacheProperties properties,
            CacheMetrics metrics) {
        return new RedissonDistributedSemaphore(redissonClient, properties.getSemaphore(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfirmationTokenStore confirmationTokenStore(
            RedissonClient redissonClient,
            CacheNamespace cacheNamespace,
            ObjectMapper objectMapper,
            CacheMetrics metrics) {
        return new RedisConfirmationTokenStore(redissonClient, cacheNamespace, objectMapper, metrics);
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
