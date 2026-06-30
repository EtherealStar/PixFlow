package com.pixflow.agent.subagent.tools;

import com.pixflow.agent.subagent.SubagentRequest;
import com.pixflow.agent.subagent.SubagentRunner;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code agent(type=explore)} 工具 handler。
 *
 * <p>对应 {@code agent.md §8.5.2}：解析入参 → SubagentRequest.explore → 阻塞 join。
 */
@Component
public class ExploreSubagentTool implements ToolHandler {

    private final SubagentRunner subagentRunner;

    public ExploreSubagentTool(SubagentRunner subagentRunner) {
        this.subagentRunner = subagentRunner;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        String type = (String) args.getOrDefault("type", "explore");
        if (!"explore".equals(type)) {
            return new ToolHandlerOutput(
                    "Error: ExploreSubagentTool only handles type=explore, got " + type,
                    Map.of("error", true)
            );
        }
        String prompt = (String) args.getOrDefault("prompt", "");
        SubagentRequest req = SubagentRequest.explore(
                invocation.conversationId(),
                invocation.toolCallId(),
                prompt
        );
        var future = subagentRunner.runAsync(req);
        var result = future.join();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent_type", "explore");
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