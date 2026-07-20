package com.pixflow.module.imagegen.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.module.imagegen.proposal.ImagegenPlanService;
import com.pixflow.module.imagegen.proposal.ImagegenPlanValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * imagegen 模块 Spring 装配(对齐 imagegen.md §十六.14)。
 *
 * <p>默认装配:纯函数能力 + handler 接线(供 harness/tools 收集);
 * <strong>不</strong>默认装配 {@link com.pixflow.module.imagegen.exec.DefaultImageGenExecutor}。
 *
 * <p>执行器装配边界:
 * <ul>
 *   <li>本类下不写 executor 的 @Bean(避免 Wave 3 范围内把 executor 暴露给无人消费的 runner)</li>
 *   <li>Wave 4 task 模块就绪后,由 task 的 AutoConfiguration 显式
 *       {@code @Import(DefaultImageGenExecutor.class)} 并在 {@code @ConditionalOnProperty} 里
 *       设置 {@code pixflow.imagegen.executor.expose=true}</li>
 *   <li>该边界由 {@code ImagegenAutoConfigurationSentinelTest} 守护:
 *       默认上下文里 {@code applicationContext.getBean(DefaultImageGenExecutor.class)} 抛
 *       {@code NoSuchBeanDefinitionException}</li>
 * </ul>
 */
@AutoConfiguration(afterName = "com.pixflow.module.dag.config.DagAutoConfiguration")
@EnableConfigurationProperties(ImagegenProperties.class)
public class ImagegenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImagegenPayloadHasher imagegenPayloadHasher(ImagegenProperties properties) {
        return new ImagegenPayloadHasher(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImagegenMetrics imagegenMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        return new ImagegenMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImagegenPlanValidator imagegenPlanValidator(ImagegenProperties properties,
                                                       com.pixflow.module.imagegen.port.SourceImageReader reader) {
        return new ImagegenPlanValidator(properties, reader);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImagegenPlanService imagegenPlanService(
            ImagegenPlanValidator validator,
            ImagegenPayloadHasher payloadHasher,
            ObjectMapper objectMapper,
            ImagegenMetrics metrics) {
        return new ImagegenPlanService(validator, payloadHasher, objectMapper, metrics);
    }

    /**
     * 装配开关:仅当显式设置 {@code pixflow.imagegen.executor.expose=true} 时,
     * 才把 {@link com.pixflow.module.imagegen.exec.DefaultImageGenExecutor} 暴露到 Spring 上下文。
     * 默认 false(由 task 模块在 Wave 4 主动打开)。
     */
    @Bean
    @ConditionalOnProperty(prefix = "pixflow.imagegen.executor", name = "expose", havingValue = "true")
    @ConditionalOnMissingBean
    public com.pixflow.module.imagegen.exec.DefaultImageGenExecutor defaultImageGenExecutor(
            com.pixflow.infra.ai.imagegen.ImageGenClient imageGenClient,
            com.pixflow.infra.storage.ObjectStorage objectStorage,
            com.pixflow.module.imagegen.port.SourceImageContent sourceImages,
            ImagegenProperties properties) {
        return new com.pixflow.module.imagegen.exec.DefaultImageGenExecutor(
            imageGenClient, objectStorage, sourceImages, properties);
    }
}
