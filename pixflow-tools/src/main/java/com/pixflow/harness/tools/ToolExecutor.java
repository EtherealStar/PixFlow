package com.pixflow.harness.tools;

import java.util.List;

public interface ToolExecutor {
    List<ToolExecutionResult> execute(List<ToolCall> calls, ToolExecutionContext context);
}
