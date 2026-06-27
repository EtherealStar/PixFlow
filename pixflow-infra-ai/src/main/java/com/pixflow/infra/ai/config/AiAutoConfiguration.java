package com.pixflow.infra.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.model.DefaultModelRouter;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.resilience.ConcurrencyGuard;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.resilience.RetryPolicy;
import com.pixflow.infra.ai.resilience.ToolCallAccumulator;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ai 自动装配。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModelRouter modelRouter(AiProperties properties) {
        return new DefaultModelRouter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy retryPolicy(AiProperties properties) {
        return new RetryPolicy(
                properties.retry().maxRetries(),
                properties.retry().baseDelay(),
                properties.retry().maxDelay(),
                properties.retry().jitterRatio());
    }

    @Bean
    @ConditionalOnMissingBean
    public AiMetrics aiMetrics(MeterRegistry meterRegistry) {
        return new AiMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConcurrencyGuard concurrencyGuard(ObjectProvider<GlobalConcurrencyLimiter> limiterProvider) {
        return new ConcurrencyGuard(limiterProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRetryRunner modelRetryRunner(RetryPolicy retryPolicy) {
        return new ModelRetryRunner(retryPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolCallAccumulator toolCallAccumulator(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ToolCallAccumulator(objectMapper);
    }
}
