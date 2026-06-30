package com.pixflow.harness.loop.config;

import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.harness.loop.permission.PermissionContextFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * loop 模块的 Spring 自动装配。
 *
 * <p>仅暴露 {@link LoopProperties}、{@link PermissionContextFactory} 两个 bean；
 * {@code AgentLoop} 由调用方（conversation / agent 层）显式构造（构造期注入所有协作 SPI），
 * 避免与 harness 基础件的装配顺序耦合。
 *
 * <p>{@code RuntimeState} 不同 conversationId 不能共享实例，必须由调用方在回合入口
 * 各自构造；本配置不提供 {@code RuntimeState} bean。
 */
@Configuration
@EnableConfigurationProperties(LoopProperties.class)
public class LoopAutoConfiguration {

    @Bean
    public PermissionContextFactory permissionContextFactory() {
        return new DefaultPermissionContextFactory();
    }
}