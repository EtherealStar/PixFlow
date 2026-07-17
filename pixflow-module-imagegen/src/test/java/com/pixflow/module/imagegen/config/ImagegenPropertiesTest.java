package com.pixflow.module.imagegen.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ImagegenProperties 配置绑定单测(对齐 imagegen.md §十三)。
 *
 * <p>覆盖默认值、自定义值、嵌套结构。
 */
class ImagegenPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(EnableConfig.class);

    @Test
    @DisplayName("默认值与 imagegen.md §十三 表格一致")
    void defaults_matchDesign() {
        runner.run(ctx -> {
            ImagegenProperties props = ctx.getBean(ImagegenProperties.class);
            // proposal
            assertThat(props.getProposal().getPromptMinChars()).isEqualTo(1);
            assertThat(props.getProposal().getPromptMaxChars()).isEqualTo(2000);
            assertThat(props.getProposal().getAllowedParamKeys())
                .containsExactly("style", "strength", "negative_prompt", "seed");
            // output
            assertThat(props.getOutput().getDefaultExt()).isEqualTo("png");
            assertThat(props.getOutput().getMaxOutputBytes()).isEqualTo(52_428_800L);
            // source
            assertThat(props.getSource().getSupportedTypes())
                .containsExactly("image/jpeg", "image/png", "image/webp");
            assertThat(props.getSource().getMaxReadBytes()).isEqualTo(52_428_800L);
            // executor 默认不装配
            assertThat(props.getExecutor().isExpose()).isFalse();
        });
    }

    @Test
    @DisplayName("自定义值:覆盖 max-output-bytes 与 executor expose")
    void customValues() {
        runner.withPropertyValues(
            "pixflow.imagegen.output.max-output-bytes=10485760",
            "pixflow.imagegen.executor.expose=true"
        ).run(ctx -> {
            ImagegenProperties props = ctx.getBean(ImagegenProperties.class);
            assertThat(props.getOutput().getMaxOutputBytes()).isEqualTo(10_485_760L);
            assertThat(props.getExecutor().isExpose()).isTrue();
        });
    }

    @EnableConfigurationProperties(ImagegenProperties.class)
    static class EnableConfig {
    }
}
