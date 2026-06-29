package com.pixflow.module.vision.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.image.ImageFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VisionPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableConfig.class);

    @Test
    void defaultsMatchDesign() {
        runner.run(ctx -> {
            VisionProperties props = ctx.getBean(VisionProperties.class);
            assertThat(props.getImage().getMaxLongEdge()).isEqualTo(1280);
            assertThat(props.getImage().getOutputFormat()).isEqualTo(ImageFormat.JPEG);
            assertThat(props.getImage().getMaxImageBytes()).isEqualTo(10L * 1024L * 1024L);
            assertThat(props.getAnalyze().getImagesPerCall()).isEqualTo(6);
            assertThat(props.getAnalyze().getSampling()).isEqualTo(VisionProperties.Sampling.MAIN_FIRST);
            assertThat(props.getEnrich().isExpose()).isFalse();
        });
    }

    @Test
    void customValuesBind() {
        runner.withPropertyValues(
                        "pixflow.vision.image.max-long-edge=960",
                        "pixflow.vision.analyze.images-per-call=3",
                        "pixflow.vision.enrich.expose=true")
                .run(ctx -> {
                    VisionProperties props = ctx.getBean(VisionProperties.class);
                    assertThat(props.getImage().getMaxLongEdge()).isEqualTo(960);
                    assertThat(props.getAnalyze().getImagesPerCall()).isEqualTo(3);
                    assertThat(props.getEnrich().isExpose()).isTrue();
                });
    }

    @EnableConfigurationProperties(VisionProperties.class)
    static class EnableConfig {
    }
}
