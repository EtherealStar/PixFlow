package com.etherealstar.pixflow.infra.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageInfraConfig {

    @Bean
    @ConditionalOnMissingBean(BackgroundRemovalClient.class)
    public BackgroundRemovalClient backgroundRemovalClient() {
        return new NaiveBackgroundRemovalClient();
    }
}
