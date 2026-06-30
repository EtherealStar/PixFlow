package com.pixflow.agent.skill;

import java.util.Objects;

/**
 * SKILL.md frontmatter 4 字段。
 *
 * <p>对应 {@code agent.md §5.3} frontmatter 强制 4 字段。
 *
 * @param name        skill 名（snake_case，唯一）
 * @param description 1-200 字简短描述（schema 层使用）
 * @param whenToUse   1-500 字触发场景（system prompt 层使用）
 * @param version     版本号（正整数）
 */
public record SkillFrontmatter(
        String name,
        String description,
        String whenToUse,
        int version
) {

    public SkillFrontmatter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(whenToUse, "whenToUse");
    }
}