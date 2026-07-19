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
    @ConditionalOnBean({VisionStateStore.class, VisualAssetReader.class})
    public VisualFactsAdministrationService visualFactsAdministrationService(
            VisionStateStore stateStore,
            VisualAssetReader assetReader,
            ProductVisualFactsNormalizer normalizer,
            Clock clock) {
        return new DefaultVisualFactsAdministrationService(stateStore, assetReader, normalizer, clock);
    }
}
