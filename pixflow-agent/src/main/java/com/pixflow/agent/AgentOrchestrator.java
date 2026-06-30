package com.pixflow.agent;

import com.pixflow.agent.memory.MemoryRecallPlanner;
import com.pixflow.agent.memory.MemoryRecallResult;
import com.pixflow.agent.memory.MemoryRecallSignal;
import com.pixflow.agent.planmode.PlanModeController;
import com.pixflow.agent.prompt.DynamicPromptAssembler;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.agent.sessionmemory.SessionMemoryService;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent 决策层入口（per-request 装配）。
 *
 * <p>对应 {@code agent.md §十二}：
 * <ol>
 *   <li>同步召回（MemoryRecallPlanner.plan）—— 用户决策 3：入口拿当次 prompt + 当次状态</li>
 *   <li>构造 PromptRuntimeContext（state + visibleTools + recall + sessionMemory）</li>
 *   <li>渲染 systemPrompt + toolSchemas</li>
 *   <li>驱动 loop（per-call AgentLoop）</li>
 * </ol>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>非 Spring 单例——per-request 装配（决策 12）</li>
 *   <li>不直接持有 ModelClient / MessageStore 实现（仅持有接口）</li>
 *   <li>错误向上抛给 web 层（归一化为 HTTP/SSE error 帧）</li>
 * </ul>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final MemoryRecallPlanner memoryRecallPlanner;
    private final SessionMemoryService sessionMemoryService;
    private final DynamicPromptAssembler promptAssembler;
    private final ToolRegistry toolRegistry;
    private final PlanModeController planModeController;

    public AgentOrchestrator(MemoryRecallPlanner memoryRecallPlanner,
                              SessionMemoryService sessionMemoryService,
                              DynamicPromptAssembler promptAssembler,
                              ToolRegistry toolRegistry,
                              PlanModeController planModeController) {
        this.memoryRecallPlanner = memoryRecallPlanner;
        this.sessionMemoryService = sessionMemoryService;
        this.promptAssembler = promptAssembler;
        this.toolRegistry = toolRegistry;
        this.planModeController = planModeController;
    }

    /**
     * 同步触发记忆召回。
     *
     * <p>对应 {@code agent.md §十二.2} 步骤 3 — 在 buildForModel 之前
     * 同步执行；本方法抽取出来便于测试。
     */
    public MemoryRecallResult recall(String userMessage,
                                      String packageId,
                                      List<String> currentPackageSkuIds,
                                      List<String> recentAssistantMessages) {
        MemoryRecallSignal signal = new MemoryRecallSignal(
                userMessage,
                packageId,
                currentPackageSkuIds,
                recentAssistantMessages,
                List.of()
        );
        return memoryRecallPlanner.plan(signal);
    }

    /**
     * 构造 PromptRuntimeContext。
     */
    public SectionRenderer.PromptRuntimeContext buildContext(
            com.pixflow.harness.loop.RuntimeState state,
            String conversationId,
            Integer turnNo,
            String packageId,
            List<String> attachedSkuIds,
            String userMessage,
            MemoryRecallResult recall,
            SessionMemoryContent sessionMemory,
            List<com.pixflow.harness.tools.ToolDescriptor> visibleTools) {
        return new SectionRenderer.PromptRuntimeContext(
                state,
                conversationId,
                turnNo,
                packageId,
                attachedSkuIds,
                List.of(),
                List.of(),
                userMessage,
                planModeController.readPlanMode(state),
                recall,
                sessionMemory,
                visibleTools
        );
    }

    /**
     * 渲染完整 systemPrompt。
     */
    public String renderSystemPrompt(SectionRenderer.PromptRuntimeContext ctx) {
        return promptAssembler.assemble(ctx);
    }

    /**
     * 装配会话内容（入口 helper）：recall + load session memory + 构造 ctx + 渲染。
     */
    public Map<String, Object> prepareTurn(
            com.pixflow.harness.loop.RuntimeState state,
            String conversationId,
            Integer turnNo,
            String packageId,
            List<String> currentPackageSkuIds,
            List<String> recentAssistantMessages,
            String userMessage,
            List<com.pixflow.harness.tools.ToolDescriptor> visibleTools) {
        // 1. 同步召回
        MemoryRecallResult recallResult = recall(userMessage, packageId,
                currentPackageSkuIds, recentAssistantMessages);
        // 2. 加载 session memory
        SessionMemoryContent sessionMemory = sessionMemoryService
                .load(conversationId).orElse(null);
        // 3. 构造 PromptRuntimeContext
        SectionRenderer.PromptRuntimeContext ctx = buildContext(
                state, conversationId, turnNo, packageId,
                currentPackageSkuIds, userMessage,
                recallResult, sessionMemory, visibleTools
        );
        // 4. 渲染 systemPrompt
        String systemPrompt = renderSystemPrompt(ctx);
        log.info("AgentOrchestrator.prepareTurn: conversationId={}, recallPlanId={}, systemPromptLen={}",
                conversationId, recallResult.recallPlanId(), systemPrompt.length());
        return Map.of(
                "systemPrompt", systemPrompt,
                "recall", recallResult
        );
    }
}