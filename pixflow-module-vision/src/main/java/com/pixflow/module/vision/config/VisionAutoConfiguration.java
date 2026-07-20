package com.pixflow.module.vision.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.application.DefaultVisualFactsAdministrationService;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.domain.VisionStateStore;
import com.pixflow.module.vision.persistence.MybatisVisionStateStore;
import com.pixflow.module.vision.persistence.VisionPersistenceConfiguration;
import com.pixflow.module.vision.persistence.VisionStateMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.pixflow.module.vision.domain.VisionInputStateStore;

@AutoConfiguration
@Import(VisionPersistenceConfiguration.class)
public class VisionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ProductVisualFactsNormalizer productVisualFactsNormalizer(ObjectProvider<ObjectMapper> objectMapper) {
        return new ProductVisualFactsNormalizer(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock visionClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VisionStateMapper.class)
    public VisionStateStore visionStateStore(VisionStateMapper mapper) {
        return new MybatisVisionStateStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VisionStateMapper.class)
    public com.pixflow.module.vision.execution.VisionExecutionStore visionExecutionStore(
            VisionStateMapper mapper) {
        return new com.pixflow.module.vision.persistence.MybatisVisionExecutionStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(com.pixflow.infra.mq.MessagePublisher.class)
    public com.pixflow.module.vision.execution.VisionWorkPublisher visionWorkPublisher(
            com.pixflow.infra.mq.MessagePublisher publisher) {
        return new com.pixflow.module.vision.execution.RocketVisionWorkPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(com.pixflow.infra.mq.MessagePublisher.class)
    public com.pixflow.module.vision.api.VisionTriggerPublisher visionTriggerPublisher(
            com.pixflow.infra.mq.MessagePublisher publisher) {
        return new com.pixflow.module.vision.execution.RocketVisionTriggerPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        VisualAssetReader.class,
        VisionInputStateStore.class,
        com.pixflow.module.vision.execution.VisionWorkPublisher.class
    })
    public com.pixflow.module.vision.application.VisionAnalysisJobCoordinator visionAnalysisJobCoordinator(
            VisualAssetReader assetReader, VisionInputStateStore inputStore,
            com.pixflow.module.vision.execution.VisionWorkPublisher publisher, Clock clock) {
        return new com.pixflow.module.vision.application.VisionAnalysisJobCoordinator(
                assetReader, inputStore, publisher, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        com.pixflow.module.vision.execution.VisionExecutionStore.class,
        org.springframework.scheduling.TaskScheduler.class
    })
    public com.pixflow.module.vision.execution.VisionHeartbeat visionHeartbeat(
            com.pixflow.module.vision.execution.VisionExecutionStore store,
            org.springframework.scheduling.TaskScheduler scheduler, Clock clock) {
        return new com.pixflow.module.vision.execution.VisionHeartbeat(store, scheduler, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        com.pixflow.module.vision.execution.VisionExecutionStore.class,
        VisualAssetReader.class,
        com.pixflow.infra.cache.lock.LockTemplate.class,
        com.pixflow.infra.ai.vision.VisionModelClient.class,
        com.pixflow.infra.image.pipeline.ImagePipeline.class,
        com.pixflow.module.vision.execution.VisionHeartbeat.class
    })
    public com.pixflow.module.vision.execution.VisionFactsWorker visionFactsWorker(
            com.pixflow.module.vision.execution.VisionExecutionStore store,
            VisualAssetReader assetReader,
            com.pixflow.infra.cache.lock.LockTemplate locks,
            com.pixflow.infra.ai.vision.VisionModelClient model,
            com.pixflow.infra.image.pipeline.ImagePipeline images,
            ProductVisualFactsNormalizer normalizer,
            ObjectMapper objectMapper,
            Clock clock,
            com.pixflow.module.vision.execution.VisionHeartbeat heartbeat) {
        return new com.pixflow.module.vision.execution.VisionFactsWorker(
                store, assetReader, locks, model, images, normalizer, objectMapper, clock, heartbeat);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        com.pixflow.module.vision.execution.VisionExecutionStore.class,
        com.pixflow.module.vision.execution.VisionWorkPublisher.class
    })
    public com.pixflow.module.vision.execution.VisionRecoveryScanner visionRecoveryScanner(
            com.pixflow.module.vision.execution.VisionExecutionStore store,
            com.pixflow.module.vision.execution.VisionWorkPublisher publisher, Clock clock) {
        return new com.pixflow.module.vision.execution.VisionRecoveryScanner(store, publisher, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        com.pixflow.module.vision.application.VisionAnalysisJobCoordinator.class,
        com.pixflow.module.vision.execution.VisionFactsWorker.class
    })
    public com.pixflow.module.vision.execution.VisionMessageHandlers visionMessageHandlers(
            com.pixflow.module.vision.application.VisionAnalysisJobCoordinator coordinator,
            com.pixflow.module.vision.execution.VisionFactsWorker worker) {
        return new com.pixflow.module.vision.execution.VisionMessageHandlers(coordinator, worker);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        VisionStateStore.class,
        VisionInputStateStore.class,
        VisualAssetReader.class,
        com.pixflow.module.vision.application.FocusedImageAnalysis.class
    })
    public com.pixflow.module.vision.api.ProductVisualFactsLookup productVisualFactsLookup(
            VisionStateStore stateStore, VisionInputStateStore inputStore,
            VisualAssetReader assetReader, ProductVisualFactsNormalizer normalizer,
            com.pixflow.module.vision.application.FocusedImageAnalysis focusedImages) {
        return new com.pixflow.module.vision.application.DefaultProductVisualFactsLookup(
                new com.pixflow.contracts.asset.CanonicalAssetReferenceCodec(), stateStore,
                inputStore, assetReader, normalizer, focusedImages);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        com.pixflow.module.vision.execution.VisionExecutionStore.class,
        com.pixflow.module.vision.execution.VisionFactsWorker.class
    })
    public com.pixflow.module.vision.application.FocusedImageAnalysis focusedImageAnalysis(
            com.pixflow.module.vision.execution.VisionExecutionStore store,
            com.pixflow.module.vision.execution.VisionFactsWorker worker,
            ProductVisualFactsNormalizer normalizer, Clock clock) {
        return new com.pixflow.module.vision.application.FocusedImageAnalysis(
                store, worker, normalizer, clock);
    }

    @Bean
    @ConditionalOnMissingBean(name = "productVisualFactsToolDescriptor")
    @ConditionalOnBean(com.pixflow.module.vision.api.ProductVisualFactsLookup.class)
    public com.pixflow.harness.tools.ToolDescriptor productVisualFactsToolDescriptor(
            com.pixflow.module.vision.api.ProductVisualFactsLookup lookup, ObjectMapper objectMapper) {
        return com.pixflow.module.vision.tool.ProductVisualFactsTool.descriptor(lookup, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({VisionStateStore.class, VisualAssetReader.class})
    public VisualFactsAdministrationService visualFactsAdministrationService(
            VisionStateStore stateStore,
            VisualAssetReader assetReader,
            ProductVisualFactsNormalizer normalizer,
            Clock clock) {
        return new DefaultVisualFactsAdministrationService(stateStore, assetReader, normalizer, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.pixflow.module.vision.execution.VisionMqErrorHandler visionMqErrorHandler() {
        return new com.pixflow.module.vision.execution.VisionMqErrorHandler();
    }

    @Bean
    @ConditionalOnBean(com.pixflow.infra.mq.destination.DestinationRegistrar.class)
    public Object visionDestinationRegistration(
            com.pixflow.infra.mq.destination.DestinationRegistrar registrar) {
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.packageDestination());
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.skuDestination());
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.itemDestination());
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.packageBinding());
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.skuBinding());
        registrar.register(com.pixflow.module.vision.execution.VisionMqDestination.itemBinding());
        return new Object();
    }

    @Bean
    @ConditionalOnBean({
        com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory.class,
        com.pixflow.module.vision.execution.VisionMessageHandlers.class
    })
    public com.pixflow.infra.mq.consumer.ManagedMessageContainer visionPackageMessageContainer(
            com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory factory,
            com.pixflow.module.vision.execution.VisionMessageHandlers handlers,
            com.pixflow.module.vision.execution.VisionMqErrorHandler errorHandler) {
        var container = factory.create(
                com.pixflow.module.vision.execution.VisionMqDestination.packageBinding(),
                com.pixflow.module.vision.execution.VisionMqConsumer.packages(handlers), errorHandler);
        container.start();
        return container;
    }

    @Bean
    @ConditionalOnBean({
        com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory.class,
        com.pixflow.module.vision.execution.VisionMessageHandlers.class
    })
    public com.pixflow.infra.mq.consumer.ManagedMessageContainer visionSkuMessageContainer(
            com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory factory,
            com.pixflow.module.vision.execution.VisionMessageHandlers handlers,
            com.pixflow.module.vision.execution.VisionMqErrorHandler errorHandler) {
        var container = factory.create(
                com.pixflow.module.vision.execution.VisionMqDestination.skuBinding(),
                com.pixflow.module.vision.execution.VisionMqConsumer.skus(handlers), errorHandler);
        container.start();
        return container;
    }

    @Bean
    @ConditionalOnBean({
        com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory.class,
        com.pixflow.module.vision.execution.VisionMessageHandlers.class
    })
    public com.pixflow.infra.mq.consumer.ManagedMessageContainer visionItemMessageContainer(
            com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory factory,
            com.pixflow.module.vision.execution.VisionMessageHandlers handlers,
            com.pixflow.module.vision.execution.VisionMqErrorHandler errorHandler) {
        var container = factory.create(
                com.pixflow.module.vision.execution.VisionMqDestination.itemBinding(),
                com.pixflow.module.vision.execution.VisionMqConsumer.items(handlers), errorHandler);
        container.start();
        return container;
    }
}
