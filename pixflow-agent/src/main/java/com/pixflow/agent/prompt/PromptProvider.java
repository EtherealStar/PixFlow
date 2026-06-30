package com.pixflow.agent.prompt;

import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.loop.RuntimeState;

import java.util.List;

/**
 * Agent 装配层的 PromptProvider SPI（loop 调用）。
 *
 * <p>对应 {@code agent.md §4.5}：
 * <ul>
 *   <li>{@link #systemPrompt(RuntimeState)}：调 DynamicPromptAssembler 拿字符串</li>
 *   <li>{@link #toolSchemas(RuntimeState)}：从 visibleTools 投影为 ToolSchemaView</li>
 * </ul>
 *
 * <p>本期实现由 {@code AgentOrchestrator} 在回合入口直接调 DynamicPromptAssembler
 * + toolRegistry.toolSchemas()；PromptProvider 接口预留供未来 loop 直接调用。
 */
public interface PromptProvider {

    String systemPrompt(RuntimeState state);

    List<ToolSchemaView> toolSchemas(RuntimeState state);
}