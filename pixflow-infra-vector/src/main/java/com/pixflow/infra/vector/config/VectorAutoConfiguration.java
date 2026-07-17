package com.pixflow.infra.vector.config;

import com.pixflow.infra.vector.QdrantVectorSearch;
import com.pixflow.infra.vector.VectorHealthIndicator;
import com.pixflow.infra.vector.VectorProperties;
import com.pixflow.infra.vector.VectorSearch;
import com.pixflow.infra.vector.observability.MicrometerVectorMetrics;
import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(VectorProperties.class)
@ConditionalOnProperty(prefix = "pixflow.vector.qdrant", name = "host")
public class VectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VectorMetrics vectorMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry != null ? new MicrometerVectorMetrics(meterRegistry) : new NoopVectorMetrics();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(VectorSearch.class)
    public VectorSearch vectorSearch(VectorProperties properties, VectorMetrics metrics) {
        // 原生 QdrantClient 由适配器独占，避免绕过只读能力边界注入管理/写 API。
        return new QdrantVectorSearch(properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(name = "vectorHealthIndicator")
    public VectorHealthIndicator vectorHealthIndicator(VectorSearch vectorSearch) {
        return new VectorHealthIndicator(vectorSearch);
    }
}
