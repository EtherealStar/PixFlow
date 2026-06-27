package com.pixflow.infra.image.config;

import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.impl.DefaultImageCodec;
import com.pixflow.infra.image.pipeline.DefaultImagePipeline;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImageProperties.class)
public class ImageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImageCodec imageCodec(ImageProperties properties) {
        return new DefaultImageCodec(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImagePipeline imagePipeline(ImageCodec imageCodec) {
        return new DefaultImagePipeline(imageCodec);
    }
}
