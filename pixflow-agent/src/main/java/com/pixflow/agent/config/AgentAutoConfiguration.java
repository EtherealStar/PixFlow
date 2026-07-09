package com.pixflow.agent.config;

import com.pixflow.agent.AgentOrchestrator;
import com.pixflow.agent.skill.SkillMapper;
import com.pixflow.agent.sessionmemory.SessionMemoryMapper;
import com.pixflow.harness.context.config.ContextAutoConfiguration;
import com.pixflow.harness.loop.AgentTurnRunner;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Agent 模块 Spring bean 装配入口。
 *
 * <p>对应 {@code agent.md §十二.3}：
 * <ul>
 *   <li>{@code @EnableConfigurationProperties(AgentProperties.class)}</li>
 *   <li>{@code @MapperScan} 扫描 skill / sessionmemory 包</li>
 *   <li>暴露 {@link AgentOrchestrator} bean（{@code @ComponentScan} 自动装配）</li>
 *   <li>额外发布 {@link AgentTurnRunner} bean（{@code agentTurnRunner}），把
 *       {@link AgentOrchestrator#streamNewTurn} 适配为 harness-loop 定义的 SPI；
 *       conversation 模块按 SPI 类型查找（{@code ObjectProvider<AgentTurnRunner>}），
 *       不需硬 import agent 类型，反依赖约束保持</li>
 *   <li>其他 bean 由本自动配置控制的模块内扫描接入</li>
 * </ul>
 */
@AutoConfiguration(after = ContextAutoConfiguration.class)
@EnableConfigurationProperties(AgentProperties.class)
@MapperScan(basePackageClasses = {SkillMapper.class, SessionMemoryMapper.class}, annotationClass = Mapper.class)
@ComponentScan(
        basePackages = "com.pixflow.agent",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AgentAutoConfiguration.class))
public class AgentAutoConfiguration {

    /**
     * 把 {@link AgentOrchestrator#streamNewTurn} 适配为 {@link AgentTurnRunner} SPI。
     * 命名 {@code agentTurnRunner} 便于 conversation 模块
     * {@code @ConditionalOnMissingBean(name=...)} 覆盖。
     */
    @Bean(name = "agentTurnRunner")
    @ConditionalOnMissingBean(name = "agentTurnRunner")
    public AgentTurnRunner agentTurnRunner(AgentOrchestrator orchestrator) {
        return (conversationId, prompt, attachments, sink) ->
                orchestrator.streamNewTurn(conversationId, prompt, attachments, sink);
    }
}
