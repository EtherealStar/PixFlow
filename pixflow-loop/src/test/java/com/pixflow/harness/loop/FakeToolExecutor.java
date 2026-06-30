package com.pixflow.harness.loop;

import com.pixflow.harness.tools.ToolCall;
import com.pixflow.harness.tools.ToolExecutionContext;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.harness.tools.ToolExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 ToolExecutor：每个 ToolCall 默认返回 success 结果，content 为工具名 + toolCallId；
 * 可由 {@link #withOverride(String, ToolExecutionResult)} 注入特定工具的自定义结果。
 */
public final class FakeToolExecutor implements ToolExecutor {

    private final Map<String, ToolExecutionResult> overrides = new HashMap<>();
    private final List<List<ToolCall>> callHistory = Collections.synchronizedList(new ArrayList<>());
    private final List<ToolExecutionContext> contextHistory = Collections.synchronizedList(new ArrayList<>());

    public FakeToolExecutor withOverride(String toolCallId, ToolExecutionResult result) {
        overrides.put(toolCallId, result);
        return this;
    }

    @Override
    public List<ToolExecutionResult> execute(List<ToolCall> calls, ToolExecutionContext context) {
        callHistory.add(new ArrayList<>(calls));
        contextHistory.add(context);
        List<ToolExecutionResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            ToolExecutionResult override = overrides.get(call.toolCallId());
            if (override != null) {
                results.add(override);
            } else {
                results.add(ToolExecutionResult.success(
                        call.toolCallId(),
                        call.toolName(),
                        "fake-output:" + call.toolName() + ":" + call.toolCallId(),
                        Map.of("mock", true)));
            }
        }
        return results;
    }

    public List<List<ToolCall>> callHistory() {
        return List.copyOf(callHistory);
    }

    public List<ToolExecutionContext> contextHistory() {
        return List.copyOf(contextHistory);
    }

    public int totalCalls() {
        return callHistory.stream().mapToInt(List::size).sum();
    }
}