package com.pixflow.infra.image.config;

import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.impl.DefaultImageCodec;
import com.pixflow.infra.image.pipeline.DefaultImagePipeline;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.image.budget.DefaultPixelBudget;
import com.pixflow.infra.image.budget.PixelBudget;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = {
        "com.sksamuel.scrimage.ImmutableImage",
        "com.sksamuel.scrimage.webp.WebpWriter",
        "net.coobird.thumbnailator.Thumbnails"
})
@EnableConfigurationProperties(ImageProperties.class)
public class ImageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImageCodec imageCodec(ImageProperties properties) {
        return new DefaultImageCodec(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public PixelBudget pixelBudget(ImageProperties properties) {
        return new DefaultPixelBudget(properties.getPixelBudget().getMaxInFlightPixels());
    }

    @Bean
    @ConditionalOnMissingBean
    public ImagePipeline imagePipeline(ImageCodec imageCodec, PixelBudget pixelBudget, ImageProperties properties) {
        ImageProperties.PixelBudget budget = properties.getPixelBudget();
        return new DefaultImagePipeline(imageCodec, pixelBudget, properties.getMaxSourcePixels(),
                properties.getMaxDimension(), budget.getTargetHeadroomFactor(), budget.getAcquireTimeout());
    }
}
