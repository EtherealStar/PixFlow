package com.pixflow.agent.prompt;

import com.pixflow.agent.planmode.PlanModeState;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.module.memory.context.MemoryContext;

import java.util.List;

/**
 * system prompt 一段的渲染入口。
 *
 * <p>每个 section 实现类负责两件事：
 * <ol>
 *   <li>从 {@link PromptRuntimeContext} 提取本段需要的输入</li>
 *   <li>计算 fingerprint（输入 hash） + 渲染 body</li>
 * </ol>
 *
 * <p>fingerprint 计算由 section 自行实现（外部零认知），与
 * {@code agent.md §4.2} 的"fingerprint 计算规则委托各 section 自己"一致。
 *
 * <p>render() 返回 {@link PromptSection}（含 key/title/body/fingerprint/cacheable）。
 * assembler 拿到 PromptSection 后查 cache，未命中调 {@link PromptSection#render()}
 * 后写回。
 */
public interface SectionRenderer {

    /**
     * section key（在 prompt 中唯一；与 cache key 一致）。
     */
    String key();

    /**
     * section 标题（Markdown 第一行）。
     */
    String title();

    /**
     * 渲染本段内容。
     *
     * <p>实现要点：
     * <ul>
     *   <li>输入为空（如 session memory 无 content）→ 返回 body="" 的 PromptSection，
     *       assembler 自动跳过该段</li>
     *   <li>fingerprint 必须稳定——相同输入产生相同 fingerprint</li>
     *   <li>不允许内部调 IO（ArchUnit 6/6 守护）</li>
     * </ul>
     */
    PromptSection render(PromptRuntimeContext ctx);

    /**
     * 装配期渲染入参：所有 section 共用同一份 immutable 上下文。
     *
     * <p>对应 {@code agent.md §4.4} 的 PromptRuntimeContext。
     */
    record PromptRuntimeContext(
            RuntimeState state,
            String conversationId,
            Integer turnNo,
            String packageId,
            List<String> attachedSkuIds,
            List<String> mentionedSkuIds,
            List<String> recentAssistantMessageIds,
            String userMessage,
            PlanModeState planMode,
            MemoryContext memoryContext,
            SessionMemoryContent sessionMemory,
            List<ToolDescriptor> visibleTools
    ) {
    }
}
