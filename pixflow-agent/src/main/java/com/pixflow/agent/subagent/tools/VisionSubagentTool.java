package com.pixflow.agent.subagent.tools;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.subagent.SubagentRequest;
import com.pixflow.agent.subagent.SubagentRunner;
import com.pixflow.agent.subagent.SubagentResult;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private final AgentProperties properties;

    public VisionSubagentTool(SubagentRunner subagentRunner, AgentProperties properties) {
        this.subagentRunner = subagentRunner;
        this.properties = properties;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        SubagentToolArguments.VisionArguments parsed;
        try {
            parsed = SubagentToolArguments.parseVision(invocation.arguments());
        } catch (IllegalArgumentException ex) {
            return invalidInput(ex.getMessage());
        }
        SubagentRequest req = SubagentRequest.vision(
                invocation.conversationId(),
                invocation.toolCallId(),
                parsed.imageIds(),
                parsed.prompt()
        );
        var future = subagentRunner.runAsync(req);
        SubagentResult result;
        try {
            result = future.get(timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return subagentError("subagent_interrupted", "Subagent execution was interrupted");
        } catch (TimeoutException ex) {
            future.cancel(true);
            return subagentError("subagent_timeout", "Subagent execution timed out");
        } catch (ExecutionException | RuntimeException ex) {
            return subagentError("subagent_failed", safeMessage(ex));
        }
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

    private int timeoutSeconds() {
        return Math.max(1, properties.getSubagent().getTimeoutSeconds());
    }

    private static ToolHandlerOutput invalidInput(String message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("error", true);
        metadata.put("error_code", "invalid_tool_input");
        metadata.put("errorCategory", "VALIDATION");
        metadata.put("recovery", "SKIP");
        return new ToolHandlerOutput(message, metadata);
    }

    private static ToolHandlerOutput subagentError(String code, String message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subagent_type", "vision");
        metadata.put("error", true);
        metadata.put("error_code", code);
        metadata.put("errorCategory", "DEPENDENCY");
        metadata.put("recovery", "SKIP");
        metadata.put("error_message", message);
        return new ToolHandlerOutput(message, metadata);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
