package com.pixflow.infra.thirdparty.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalClient;
import com.pixflow.infra.thirdparty.bgremoval.RoutingBackgroundRemovalClient;
import com.pixflow.infra.thirdparty.bgremoval.provider.BackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.aliyunmarket.AliyunMarketBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.async.AsyncPollingBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.configurable.ConfigurableHttpBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.removebg.RemoveBgBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import com.pixflow.infra.thirdparty.http.DefaultThirdPartyAuthStrategy;
import com.pixflow.infra.thirdparty.http.RestClientThirdPartyHttpInvoker;
import com.pixflow.infra.thirdparty.http.ThirdPartyAuthStrategy;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallTemplate;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyResilienceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(ThirdPartyProperties.class)
public class ThirdPartyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestClient restClient(ThirdPartyProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.http().connectTimeout());
        requestFactory.setReadTimeout(properties.http().readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThirdPartyErrorMapper thirdPartyErrorMapper() {
        return new ThirdPartyErrorMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThirdPartyMetrics thirdPartyMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        return registry == null ? new ThirdPartyMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()) : new ThirdPartyMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientThirdPartyHttpInvoker thirdPartyHttpInvoker(RestClient restClient) {
        return new RestClientThirdPartyHttpInvoker(restClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThirdPartyAuthStrategy thirdPartyAuthStrategy() {
        return new DefaultThirdPartyAuthStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThirdPartyResilienceRegistry thirdPartyResilienceRegistry(ThirdPartyProperties properties) {
        return new ThirdPartyResilienceRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheNamespace thirdPartyCacheNamespace() {
        return new DefaultCacheNamespace("local", Duration.ofMinutes(5));
    }

    @Bean
    @ConditionalOnMissingBean
    public ThirdPartyCallTemplate thirdPartyCallTemplate(
            DistributedSemaphore distributedSemaphore,
            DistributedTokenBucket distributedTokenBucket,
            CacheNamespace cacheNamespace,
            ThirdPartyResilienceRegistry resilienceRegistry,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyProperties properties) {
        return new ThirdPartyCallTemplate(
                distributedSemaphore,
                distributedTokenBucket,
                cacheNamespace,
                resilienceRegistry,
                errorMapper,
                properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public List<BackgroundRemovalProvider> backgroundRemovalProviders(
            ThirdPartyProperties properties,
            ThirdPartyCallTemplate callTemplate,
            RestClientThirdPartyHttpInvoker httpInvoker,
            ThirdPartyAuthStrategy authStrategy,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyMetrics metrics,
            ObjectMapper objectMapper) {
        List<BackgroundRemovalProvider> providers = new ArrayList<>();
        for (Map.Entry<String, ThirdPartyProperties.Provider> entry : properties.providers().entrySet()) {
            String providerId = entry.getKey();
            ThirdPartyProperties.Provider provider = entry.getValue();
            if (provider == null || !provider.enabled()) {
                continue;
            }
            String type = provider.type() == null ? "" : provider.type().trim().toLowerCase();
            if ("removebg".equals(type)) {
                providers.add(new RemoveBgBackgroundRemovalProvider(providerId, provider, callTemplate, httpInvoker, authStrategy, errorMapper, metrics));
            } else if ("aliyun-market-bgrem".equals(type) || "aliyun-market".equals(type)) {
                providers.add(new AliyunMarketBackgroundRemovalProvider(providerId, provider, callTemplate, httpInvoker, authStrategy, errorMapper, metrics, objectMapper));
            } else if ("configurable-http".equals(type)) {
                providers.add(new ConfigurableHttpBackgroundRemovalProvider(providerId, provider, callTemplate, httpInvoker, authStrategy, errorMapper, metrics, objectMapper));
            } else if ("async".equals(type) || "async-polling".equals(type)) {
                providers.add(new AsyncPollingBackgroundRemovalProvider(providerId, provider, callTemplate, httpInvoker, authStrategy, errorMapper, metrics, objectMapper));
            }
        }
        return providers;
    }

    @Bean
    @ConditionalOnMissingBean
    public BackgroundRemovalClient backgroundRemovalClient(ThirdPartyProperties properties, List<BackgroundRemovalProvider> providers) {
        return new RoutingBackgroundRemovalClient(properties, providers);
    }
}
