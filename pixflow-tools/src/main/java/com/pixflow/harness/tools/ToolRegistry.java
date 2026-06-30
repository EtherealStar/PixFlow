package com.pixflow.harness.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ToolRegistry {
    Optional<ToolDescriptor> get(String name);

    List<ToolDescriptor> visibleDescriptors(ToolVisibilityContext context);

    List<Map<String, Object>> toolSchemas(ToolVisibilityContext context);

    List<String> toolPromptSections(ToolVisibilityContext context);

    /**
     * 运行时注册动态工具（如 Skill 注册的 skill__<name>）。
     *
     * <p>对应 {@code agent.md §5.6}：启动期一次性注册后所有回合可见；
     * 重复名抛 IllegalStateException。
     *
     * <p>本期用于 SkillToolRegistrar 注入 skill 工具；未来可扩展到 MCP 工具等。
     */
    default void registerDynamic(ToolDescriptor descriptor) {
        throw new UnsupportedOperationException(
                "registerDynamic is not implemented by " + getClass().getSimpleName());
    }

    /**
     * 运行时注销动态工具（本期保留，未调用）。
     */
    default void unregisterDynamic(String name) {
        throw new UnsupportedOperationException(
                "unregisterDynamic is not implemented by " + getClass().getSimpleName());
    }
}
