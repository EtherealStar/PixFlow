package com.pixflow.module.vision.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.api.ProductVisualFactsLookup;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.domain.VisionInputStateStore;
import com.pixflow.module.vision.domain.VisionStateStore;
import com.pixflow.module.vision.persistence.MybatisVisionStateStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class VisionAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, VisionAutoConfiguration.class));

    @Test
    void missingRequiredDependenciesFailsFast() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isNotNull();
        });
    }

    @Test
    void productionMybatisPathEnablesVisionStateAndAdministrationServices() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        MybatisPlusAutoConfiguration.class,
                        VisionAutoConfiguration.class))
                .withUserConfiguration(ProductionStubs.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ProductVisualFactsNormalizer.class);
                    assertThat(context).hasSingleBean(MybatisVisionStateStore.class);
                    assertThat(context).hasSingleBean(VisionStateStore.class);
                    assertThat(context).hasSingleBean(VisionInputStateStore.class);
                    assertThat(context).hasSingleBean(VisualFactsAdministrationService.class);
                    assertThat(context).hasSingleBean(ProductVisualFactsLookup.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ProductionStubs {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        VisualAssetReader visualAssetReader() {
            return mock(VisualAssetReader.class);
        }

        @Bean
        MessagePublisher messagePublisher() {
            return mock(MessagePublisher.class);
        }

        @Bean
        TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }

        @Bean
        LockTemplate lockTemplate() {
            return mock(LockTemplate.class);
        }

        @Bean
        VisionModelClient visionModelClient() {
            return mock(VisionModelClient.class);
        }

        @Bean
        ImagePipeline imagePipeline() {
            return mock(ImagePipeline.class);
        }

        @Bean
        DestinationRegistrar destinationRegistrar() {
            return mock(DestinationRegistrar.class);
        }

        @Bean
        ManagedListenerContainerFactory managedListenerContainerFactory() {
            ManagedMessageContainer container = mock(ManagedMessageContainer.class);
            ManagedListenerContainerFactory factory = mock(ManagedListenerContainerFactory.class);
            when(factory.create(any(), any(), any())).thenReturn(container);
            return factory;
        }
    }
}
