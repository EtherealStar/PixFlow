package com.pixflow.infra.thirdparty.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
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
    }
}
