package com.pixflow.harness.loop.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.loop.permission.PermissionContextFactory;
import com.pixflow.harness.permission.PermissionContext;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class LoopAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoopAutoConfiguration.class));

    @Test
    void createsManagedToolExecutorAndDefaultPermissionFactory() {
        contextRunner.run(context -> {
            assertThat(context).hasBean(LoopAutoConfiguration.LOOP_TOOL_EXECUTOR_BEAN);
            assertThat(context).hasSingleBean(PermissionContextFactory.class);
            assertThat(context.getBean(LoopAutoConfiguration.LOOP_TOOL_EXECUTOR_BEAN))
                    .isInstanceOf(GracefulThreadPoolExecutor.class);
        });
    }

    @Test
    void customPermissionFactoryWins() {
        contextRunner.withUserConfiguration(CustomPermissionFactoryConfig.class)
                .run(context -> assertThat(context.getBean(PermissionContextFactory.class))
                        .isSameAs(context.getBean("customPermissionFactory")));
    }

    @Test
    void customNamedExecutorWins() {
        contextRunner.withUserConfiguration(CustomExecutorConfig.class)
                .run(context -> assertThat(context.getBean(LoopAutoConfiguration.LOOP_TOOL_EXECUTOR_BEAN))
                        .isSameAs(context.getBean(ExecutorService.class)));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPermissionFactoryConfig {
        @Bean
        PermissionContextFactory customPermissionFactory() {
            return state -> new PermissionContext("custom", null, null, java.util.Set.of(), java.util.Set.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorConfig {
        @Bean(name = LoopAutoConfiguration.LOOP_TOOL_EXECUTOR_BEAN)
        ExecutorService customLoopToolExecutor() {
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        }
    }
}
