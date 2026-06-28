package com.pixflow.infra.vector.config;

import com.pixflow.infra.vector.QdrantVectorStore;
import com.pixflow.infra.vector.VectorHealthIndicator;
import com.pixflow.infra.vector.VectorProperties;
import com.pixflow.infra.vector.VectorStore;
import com.pixflow.infra.vector.observability.MicrometerVectorMetrics;
import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(VectorProperties.class)
@ConditionalOnProperty(prefix = "pixflow.vector", name = "host")
public class VectorAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public QdrantClient qdrantClient(VectorProperties properties) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                properties.getHost(),
                properties.getPort(),
                properties.isUseTls());
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.withApiKey(properties.getApiKey());
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorMetrics vectorMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry != null ? new MicrometerVectorMetrics(meterRegistry) : new NoopVectorMetrics();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public QdrantVectorStore qdrantVectorStore(QdrantClient qdrantClient, VectorProperties properties, VectorMetrics metrics) {
        return new QdrantVectorStore(qdrantClient, properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(QdrantVectorStore qdrantVectorStore) {
        return qdrantVectorStore;
    }

    @Bean
    @ConditionalOnBean(QdrantVectorStore.class)
    @ConditionalOnMissingBean(name = "vectorHealthIndicator")
    public HealthIndicator vectorHealthIndicator(QdrantVectorStore qdrantVectorStore) {
        return new VectorHealthIndicator(qdrantVectorStore);
    }
}
