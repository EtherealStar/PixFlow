package com.pixflow.infra.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.DefaultChatModelClient;
import com.pixflow.infra.ai.embedding.DefaultEmbeddingClient;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.imagegen.DefaultImageGenClient;
import com.pixflow.infra.ai.imagegen.ImageGenClient;
import com.pixflow.infra.ai.model.DefaultModelRouter;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.provider.DashScopeHttpClient;
import com.pixflow.infra.ai.rerank.DefaultRerankClient;
import com.pixflow.infra.ai.rerank.RerankClient;
import com.pixflow.infra.ai.resilience.ConcurrencyGuard;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.resilience.RetryPolicy;
import com.pixflow.infra.ai.resilience.ToolCallAccumulator;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import com.pixflow.infra.ai.vision.DefaultVisionModelClient;
import com.pixflow.infra.ai.vision.VisionModelClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ai 自动装配。
 */
@AutoConfiguration
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

    @Bean
    @ConditionalOnMissingBean
    public DashScopeHttpClient dashScopeHttpClient(
            AiProperties properties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider) {
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);
        return new DashScopeHttpClient(properties, webClientBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatModelClient chatModelClient(
            AiProperties properties,
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            AiMetrics aiMetrics,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);
        return new DefaultChatModelClient(
                properties,
                modelRouter,
                retryRunner,
                concurrencyGuard,
                aiMetrics,
                objectMapper,
                webClientBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionModelClient visionModelClient(ChatModelClient chatModelClient) {
        return new DefaultVisionModelClient(chatModelClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingClient embeddingClient(
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            AiMetrics aiMetrics,
            DashScopeHttpClient dashScopeHttpClient) {
        return new DefaultEmbeddingClient(modelRouter, retryRunner, concurrencyGuard, aiMetrics, dashScopeHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImageGenClient imageGenClient(
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            AiMetrics aiMetrics,
            DashScopeHttpClient dashScopeHttpClient) {
        return new DefaultImageGenClient(modelRouter, retryRunner, concurrencyGuard, aiMetrics, dashScopeHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public RerankClient rerankClient(
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            AiMetrics aiMetrics,
            DashScopeHttpClient dashScopeHttpClient) {
        return new DefaultRerankClient(modelRouter, retryRunner, concurrencyGuard, aiMetrics, dashScopeHttpClient);
    }
}
