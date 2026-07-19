package com.pixflow.module.vision.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.domain.VisionStateStore;
import com.pixflow.module.vision.persistence.VisionStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class VisionAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, VisionAutoConfiguration.class));

    @Test
    void defaultContextContainsOnlyDependencyFreeFactsComponents() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ProductVisualFactsNormalizer.class);
            assertThat(context).doesNotHaveBean(VisionStateStore.class);
            assertThat(context).doesNotHaveBean(VisualFactsAdministrationService.class);
        });
    }

    @Test
    void stateAndAssetPortsEnableAdministrationService() {
        runner.withUserConfiguration(StateStubs.class).run(context -> {
            assertThat(context).hasSingleBean(VisionStateStore.class);
            assertThat(context).hasSingleBean(VisualFactsAdministrationService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StateStubs {
        @Bean
        VisionStateMapper visionStateMapper() {
            return mock(VisionStateMapper.class);
        }

        @Bean
        VisualAssetReader visualAssetReader() {
            return mock(VisualAssetReader.class);
        }
    }
}
