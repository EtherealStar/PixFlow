package com.pixflow.module.vision.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.topology.TopologyRegistrar;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.vision.DefaultVisionService;
import com.pixflow.module.vision.VisionService;
import com.pixflow.module.vision.analyze.AssessmentParser;
import com.pixflow.module.vision.analyze.VisionAnalysisRequestValidator;
import com.pixflow.module.vision.analyze.VisionPromptBuilder;
import com.pixflow.module.vision.enrich.AssetCopyWriteMapper;
import com.pixflow.module.vision.enrich.AssetImageReadMapper;
import com.pixflow.module.vision.enrich.CopyEnrichmentConsumer;
import com.pixflow.module.vision.enrich.CopyEnrichmentErrorHandler;
import com.pixflow.module.vision.enrich.CopyEnrichmentMessage;
import com.pixflow.module.vision.enrich.CopyEnrichmentTopology;
import com.pixflow.module.vision.enrich.CopyFillPolicy;
import com.pixflow.module.vision.enrich.ProductCopyExtractor;
import com.pixflow.module.vision.image.VisionImagePreprocessor;
import com.pixflow.module.vision.image.VisionImageResolver;
import com.pixflow.module.vision.metrics.VisionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(VisionProperties.class)
public class VisionServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VisionAnalysisRequestValidator visionAnalysisRequestValidator() {
        return new VisionAnalysisRequestValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionImageResolver visionImageResolver(ObjectStorage objectStorage) {
        return new VisionImageResolver(objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionImagePreprocessor visionImagePreprocessor(ImagePipeline imagePipeline, VisionProperties properties) {
        return new VisionImagePreprocessor(imagePipeline, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionPromptBuilder visionPromptBuilder() {
        return new VisionPromptBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public AssessmentParser assessmentParser(ObjectProvider<ObjectMapper> objectMapper) {
        return new AssessmentParser(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionMetrics visionMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new VisionMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public VisionService visionService(
            VisionAnalysisRequestValidator validator,
            VisionImageResolver imageResolver,
            VisionImagePreprocessor imagePreprocessor,
            VisionPromptBuilder promptBuilder,
            AssessmentParser assessmentParser,
            VisionModelClient visionModelClient,
            VisionProperties properties,
            VisionMetrics metrics) {
        return new DefaultVisionService(
                validator,
                imageResolver,
                imagePreprocessor,
                promptBuilder,
                assessmentParser,
                visionModelClient,
                properties,
                metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public CopyFillPolicy copyFillPolicy(VisionProperties properties) {
        return new CopyFillPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProductCopyExtractor productCopyExtractor(VisionService visionService, VisionProperties properties) {
        return new ProductCopyExtractor(visionService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AssetImageReadMapper.class, AssetCopyWriteMapper.class})
    public CopyEnrichmentConsumer copyEnrichmentConsumer(
            AssetImageReadMapper imageReadMapper,
            AssetCopyWriteMapper copyWriteMapper,
            ProductCopyExtractor productCopyExtractor,
            CopyFillPolicy fillPolicy,
            VisionMetrics metrics) {
        return new CopyEnrichmentConsumer(imageReadMapper, copyWriteMapper, productCopyExtractor, fillPolicy, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public CopyEnrichmentErrorHandler copyEnrichmentErrorHandler() {
        return new CopyEnrichmentErrorHandler();
    }

    @Bean
    @ConditionalOnBean(TopologyRegistrar.class)
    public Object visionCopyEnrichmentTopologyRegistration(TopologyRegistrar registrar) {
        registrar.register(CopyEnrichmentTopology.topology());
        return new Object();
    }

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, CopyEnrichmentConsumer.class, CopyEnrichmentErrorHandler.class})
    public MessageListenerContainer visionCopyEnrichmentListenerContainer(
            ManagedListenerContainerFactory factory,
            CopyEnrichmentConsumer consumer,
            CopyEnrichmentErrorHandler errorHandler) {
        MessageListenerContainer container = factory.create(
                CopyEnrichmentTopology.topology(),
                CopyEnrichmentMessage.class,
                consumer,
                errorHandler);
        container.start();
        return container;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(SqlSessionFactory.class)
    @MapperScan("com.pixflow.module.vision.enrich")
    static class VisionEnrichMapperConfiguration {
    }
}
