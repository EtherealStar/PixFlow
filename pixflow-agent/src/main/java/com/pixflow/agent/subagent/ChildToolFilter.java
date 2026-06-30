package com.pixflow.agent.subagent;

import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.harness.tools.ToolVisibilityContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * child runtime 工具集裁剪。
 *
 * <p>对应 {@code agent.md §8.4}：
 * <ul>
 *   <li>VISION / EXPLORE：core 7 工具，禁用 submit_image_plan / submit_imagegen_plan / plan</li>
 *   <li>SUMMARIZATION / SESSION_MEMORY_EXTRACTION：全部禁用 7 核心工具</li>
 * </ul>
 */
@Component
public class ChildToolFilter {

    private static final Set<String> CORE_WRITE_TOOLS = Set.of(
            "submit_image_plan", "submit_imagegen_plan", "plan"
    );
    private static final Set<String> PLAN_TOOLS = Set.of("plan", "plan_exit");

    /**
     * 构造 child 工具集（已过滤的 ToolDescriptor 列表）。
     */
    public List<ToolDescriptor> build(SubagentType type, ToolRegistry parentRegistry,
                                       ToolVisibilityContext parentVisibilityCtx) {
        if (parentRegistry == null) return List.of();
        List<ToolDescriptor> parentVisible = parentRegistry.visibleDescriptors(parentVisibilityCtx);
        return switch (type) {
            case VISION, EXPLORE -> parentVisible.stream()
                    .filter(d -> !CORE_WRITE_TOOLS.contains(d.name()))
                    .filter(d -> !PLAN_TOOLS.contains(d.name()))
                    .toList();
            case SUMMARIZATION, SESSION_MEMORY_EXTRACTION -> List.of();
        };
    }
}