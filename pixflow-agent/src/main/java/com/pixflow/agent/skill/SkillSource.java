package com.pixflow.agent.skill;

/**
 * Skill 来源（对应 skill 表 source 字段）。
 *
 * <p>对应 {@code agent.md §5.4}：BUILTIN / PROJECT / TEAN。
 * 本期只启用 BUILTIN，其余 enum 值保留扩展。
 */
public enum SkillSource {
    /** 仓库 classpath 资源 skills/&lt;name&gt;/SKILL.md */
    BUILTIN,
    /** 项目级配置（本期未启用） */
    PROJECT,
    /** 团队级运营上传（本期未启用） */
    TEAM
}