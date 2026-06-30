package com.pixflow.agent.subagent.tools;

import com.pixflow.agent.subagent.SubagentRequest;
import com.pixflow.agent.subagent.SubagentRunner;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code agent(type=vision)} 工具 handler。
 *
 * <p>对应 {@code agent.md §8.5.1}：解析入参 → 构造 SubagentRequest → 阻塞 join
 * SubagentRunner.runAsync → 返回 ToolHandlerOutput。
 *
 * <p>join 阻塞但发生在 tools 执行管线的并发池（max=8），不阻塞主回合线程。
 */
@Component
public class VisionSubagentTool implements ToolHandler {

    private static final String TOOL_NAME = "agent";

    private final SubagentRunner subagentRunner;

    public VisionSubagentTool(SubagentRunner subagentRunner) {
        this.subagentRunner = subagentRunner;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        String type = (String) args.getOrDefault("type", "vision");
        if (!"vision".equals(type)) {
            return new ToolHandlerOutput(
                    "Error: VisionSubagentTool only handles type=vision, got " + type,
                    Map.of("error", true)
            );
        }
        @SuppressWarnings("unchecked")
        List<String> imageIds = (List<String>) args.getOrDefault("imageIds", List.of());
        String prompt = (String) args.getOrDefault("prompt", "");
        SubagentRequest req = SubagentRequest.vision(
                invocation.conversationId(),
                invocation.toolCallId(),
                imageIds,
                prompt
        );
        var future = subagentRunner.runAsync(req);
        var result = future.join();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent_type", "vision");
        metadata.put("usage", result.usage());
        metadata.put("tool_result_count", result.toolResultCount());
        metadata.put("child_tool_spans", result.childToolSpans());
        metadata.put("error", result.isError());
        if (result.isError()) {
            metadata.put("error_message", result.errorMessage());
        }
        return new ToolHandlerOutput(result.finalText(), metadata);
    }
}