package com.pixflow.module.imagegen.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.time.TimeAutoConfiguration;
import com.pixflow.contracts.proposal.ProposalPublicationPort;
import com.pixflow.infra.ai.imagegen.ImageGenClient;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.imagegen.exec.DefaultImageGenExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配边界 Sentinel 单测(对齐 imagegen.md §三 / §十六.14)。
 *
 * <p>守护:默认加载 imagegen 模块的 Spring 上下文时,DefaultImageGenExecutor 不应被装配;
 * 显式设置 {@code pixflow.imagegen.executor.expose=true} 后,装配器才把 executor 暴露到容器。
 */
class ImagegenAutoConfigurationSentinelTest {

    private final ApplicationContextRunner defaultRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            JacksonAutoConfiguration.class,
            TimeAutoConfiguration.class,
            ImagegenAutoConfiguration.class))
        // Provide MeterRegistry + SPI / 基础设施 mock bean so the context can be built
        // (test isolates only executor exposure)
        .withUserConfiguration(TestStubs.class);

    @Test
    @DisplayName("默认(Default):DefaultImageGenExecutor 不在 Spring 容器中")
    void default_imageGenExecutor_isNotExposed() {
        defaultRunner.run(ctx -> {
            // 上下文能起来（只缺 ProposalPublicationPort / SourceImageReader SPI）
            // 但 DefaultImageGenExecutor 必须不在容器内
            assertThatThrownBy(() -> ctx.getBean(DefaultImageGenExecutor.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        });
    }

    @Test
    @DisplayName("默认(Default):imagegen 服务消费 contracts ProposalPublicationPort")
    void default_imagegenServices_useContractsProposalPublicationPort() {
        defaultRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ProposalPublicationPort.class);
            assertThat(ctx).hasSingleBean(com.pixflow.module.imagegen.proposal.ImagegenPlanService.class);
            assertThat(ctx).hasBean("submitImagegenPlanDescriptor");
        });
    }

    @Test
    @DisplayName("显式 expose=true:DefaultImageGenExecutor 进入 Spring 容器")
    void exposeTrue_imageGenExecutor_isExposed() {
        defaultRunner
            .withPropertyValues(
                "pixflow.imagegen.executor.expose=true"
            )
            .run(ctx -> {
                DefaultImageGenExecutor bean = ctx.getBean(DefaultImageGenExecutor.class);
                assertThat(bean).isNotNull();
            });
    }

    @Test
    @DisplayName("显式 expose=false:DefaultImageGenExecutor 不在容器中(明示 false)")
    void exposeFalse_imageGenExecutor_isNotExposed() {
        defaultRunner
            .withPropertyValues(
                "pixflow.imagegen.executor.expose=false"
            )
            .run(ctx -> {
                assertThatThrownBy(() -> ctx.getBean(DefaultImageGenExecutor.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            });
    }

    /** SPI / 基础设施 mock,让 Spring 上下文能起来。仅供 sentinel 测试隔离 executor 装配行为。 */
    @Configuration(proxyBeanMethods = false)
    static class TestStubs {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ImageGenClient imageGenClient() {
            return req -> new com.pixflow.infra.ai.imagegen.ImageGenResult(
                new byte[]{1, 2, 3}, "image/png", new com.pixflow.infra.ai.model.TokenUsage(0L, 0L, 0L));
        }

        @Bean
        ObjectStorage objectStorage() {
            return new com.pixflow.infra.storage.ObjectStorage() {
                @Override public com.pixflow.infra.storage.ObjectRef put(com.pixflow.infra.storage.ObjectLocation loc,
                    java.io.InputStream data, long size, String contentType) {
                    return new com.pixflow.infra.storage.ObjectRef(loc.bucket(), loc.key(), size, "etag");
                }
                @Override public java.io.InputStream getStream(com.pixflow.infra.storage.ObjectLocation loc) {
                    return new java.io.ByteArrayInputStream(new byte[]{1, 2, 3});
                }
                @Override public byte[] getBytes(com.pixflow.infra.storage.ObjectLocation loc) {
                    return new byte[]{1, 2, 3};
                }
                @Override public boolean exists(com.pixflow.infra.storage.ObjectLocation loc) { return true; }
                @Override public com.pixflow.infra.storage.StoredObjectMetadata stat(com.pixflow.infra.storage.ObjectLocation loc) {
                    return new com.pixflow.infra.storage.StoredObjectMetadata(3L, "image/png", "etag", java.time.Instant.now());
                }
                @Override public void delete(com.pixflow.infra.storage.ObjectLocation loc) {}
                @Override public void deleteByPrefix(com.pixflow.infra.storage.BucketType bucket, String prefix) {}
                @Override public com.pixflow.infra.storage.ObjectRef copy(
                        com.pixflow.infra.storage.ObjectLocation source,
                        com.pixflow.infra.storage.ObjectLocation target) {
                    return new com.pixflow.infra.storage.ObjectRef(
                            target.bucket(), target.key(), 3L, "etag");
                }
                @Override public java.net.URL presignGet(com.pixflow.infra.storage.ObjectLocation loc, java.time.Duration ttl) {
                    throw new UnsupportedOperationException("not used in sentinel test");
                }
                @Override public java.net.URL presignPut(com.pixflow.infra.storage.ObjectLocation loc, java.time.Duration ttl) {
                    throw new UnsupportedOperationException("not used in sentinel test");
                }
            };
        }

        @Bean
        ProposalPublicationPort proposalPublicationPort() {
            return proposal -> {
                    return "test-plan-id";
            };
        }

        @Bean
        com.pixflow.module.imagegen.port.SourceImageReader sourceImageReader() {
            return (imageIds, packageId) -> java.util.List.of();
        }
    }
}
