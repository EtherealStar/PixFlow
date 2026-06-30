package com.pixflow.agent.skill;

import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 工具 handler。
 *
 * <p>对应 {@code agent.md §5.7}：handler 自身<b>不调 LLM</b>，纯读 + 缓存命中。
 *
 * <p>handler 流程：
 * <ol>
 *   <li>从 {@code inv.toolName()} 剥 {@code skill__} 前缀</li>
 *   <li>{@code skillRepository.findByName(name)}</li>
 *   <li>不存在 → 抛 SkillNotFoundException（→ AGENT_SKILL_NOT_FOUND）</li>
 *   <li>返回 {@code ToolHandlerOutput(body, metadata)}</li>
 * </ol>
 *
 * <p>body 长度 > 50KB 走 {@code ToolResultStorage} 外置
 * —— 由 tools 执行管线统一处理（ToolResultPolicy），handler 不直接处理。
 */
@Component
public class SkillHandler implements ToolHandler {

    private static final String PREFIX = "skill__";

    private final SkillRepository skillRepository;

    public SkillHandler(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        String toolName = invocation.toolName();
        if (!toolName.startsWith(PREFIX)) {
            throw new IllegalArgumentException("SkillHandler received non-skill toolName: " + toolName);
        }
        String skillName = toolName.substring(PREFIX.length());
        Skill skill = skillRepository.findByName(skillName)
                .orElseThrow(() -> new SkillNotFoundException(skillName));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skill_name", skill.getName());
        metadata.put("skill_version", skill.getVersion());
        metadata.put("body_chars", skill.getBody() == null ? 0 : skill.getBody().length());
        return new ToolHandlerOutput(skill.getBody(), metadata);
    }
}