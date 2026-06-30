package com.pixflow.agent.skill;

/**
 * skill 工具调用时 skill 不存在。
 *
 * <p>对应 {@code agent.md §5.12} `AGENT_SKILL_NOT_FOUND` 错误码触发场景。
 */
public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String skillName) {
        super("Unknown skill: " + skillName);
    }
}