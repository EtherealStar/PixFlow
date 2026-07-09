package com.pixflow.harness.loop.config;

import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.harness.loop.permission.PermissionContextFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * loop 模块的 Spring 自动装配。
 *
 * <p>暴露 {@link LoopProperties}、{@link PermissionContextFactory} 与命名
 * {@code loopToolExecutor}。工具 executor 是进程级资源，由 Spring 生命周期管理；
 * {@code AgentLoop} 由调用方（conversation / agent 层）显式构造（构造期注入所有协作 SPI），
 * 避免与 harness 基础件的装配顺序耦合。
 *
 * <p>{@code RuntimeState} 不同 conversationId 不能共享实例，必须由调用方在回合入口
 * 各自构造；本配置不提供 {@code RuntimeState} bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(LoopProperties.class)
public class LoopAutoConfiguration {
    public static final String LOOP_TOOL_EXECUTOR_BEAN = "loopToolExecutor";

    @Bean
    @ConditionalOnMissingBean(PermissionContextFactory.class)
    public PermissionContextFactory permissionContextFactory() {
        return new DefaultPermissionContextFactory();
    }

    @Bean(name = LOOP_TOOL_EXECUTOR_BEAN, destroyMethod = "shutdownGracefully")
    @ConditionalOnMissingBean(name = LOOP_TOOL_EXECUTOR_BEAN)
    public ExecutorService loopToolExecutor(LoopProperties properties) {
        LoopProperties loopProperties = properties == null ? new LoopProperties() : properties;
        int poolSize = loopProperties.toolConcurrencyPoolSize();
        return new GracefulThreadPoolExecutor(
                poolSize,
                new LinkedBlockingQueue<>(loopProperties.toolQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "loop-tool");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy(),
                loopProperties.toolShutdownTimeoutSeconds());
    }
}
