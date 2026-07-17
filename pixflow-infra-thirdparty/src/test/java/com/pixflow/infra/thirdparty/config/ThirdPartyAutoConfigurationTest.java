package com.pixflow.infra.thirdparty.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.cache.tokenbucket.DistributedTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ThirdPartyAutoConfigurationTest {

    @Test
    void loadsWithoutProviders() {
        SpringApplication app = new SpringApplication(TestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(java.util.Map.of("pixflow.thirdparty.bg-removal.default-provider", "missing"));
        try (ConfigurableApplicationContext context = app.run()) {
            assertThat(context.getBean(BackgroundRemovalClient.class)).isNotNull();
        }
    }

    @Test
    void bindsOutboundQuotaPolicy() {
        SpringApplication app = new SpringApplication(TestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(java.util.Map.of(
                "pixflow.thirdparty.bg-removal.default-provider", "missing",
                "pixflow.thirdparty.outbound-quota.p1.bg-removal.capacity", "20",
                "pixflow.thirdparty.outbound-quota.p1.bg-removal.refill-tokens", "5",
                "pixflow.thirdparty.outbound-quota.p1.bg-removal.refill-period", "2s",
                "pixflow.thirdparty.outbound-quota.p1.bg-removal.idle-ttl", "3m",
                "pixflow.thirdparty.outbound-quota.p1.bg-removal.cost-per-attempt", "2"));

        try (ConfigurableApplicationContext context = app.run()) {
            ThirdPartyProperties.OutboundQuota quota = context.getBean(ThirdPartyProperties.class)
                    .outboundQuota("p1", "bg-removal");

            assertThat(quota.capacity()).isEqualTo(20);
            assertThat(quota.refillTokens()).isEqualTo(5);
            assertThat(quota.refillPeriod()).isEqualTo(Duration.ofSeconds(2));
            assertThat(quota.idleTtl()).isEqualTo(Duration.ofMinutes(3));
            assertThat(quota.costPerAttempt()).isEqualTo(2);
        }
    }

    @Configuration
    @Import(ThirdPartyAutoConfiguration.class)
    static class TestApp {
        @Bean
        DistributedSemaphore distributedSemaphore() {
            return new DistributedSemaphore() {
                @Override
                public Permit acquire(CacheKey key, int permits, Duration waitTime) {
                    return () -> {
                    };
                }
            };
        }

        @Bean
        CacheNamespace cacheNamespace() {
            return new com.pixflow.infra.cache.key.DefaultCacheNamespace("test", Duration.ofMinutes(1));
        }

        @Bean
        DistributedTokenBucket distributedTokenBucket() {
            return (key, policy, cost) -> new TokenBucketDecision(
                    true, policy.capacity() - cost, Duration.ZERO);
        }
    }
}
