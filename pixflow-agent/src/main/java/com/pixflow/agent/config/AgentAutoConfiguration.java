package com.pixflow.agent.config;

import com.pixflow.agent.skill.SkillMapper;
import com.pixflow.agent.sessionmemory.SessionMemoryMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模块 Spring bean 装配入口。
 *
 * <p>对应 {@code agent.md §十二.3}：
 * <ul>
 *   <li>{@code @EnableConfigurationProperties(AgentProperties.class)}</li>
 *   <li>{@code @MapperScan} 扫描 skill / sessionmemory 包</li>
 *   <li>其他 bean 全部用 {@code @Component} 直接标注，Spring 自动扫描</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
@MapperScan(basePackageClasses = {SkillMapper.class, SessionMemoryMapper.class})
public class AgentAutoConfiguration {
}