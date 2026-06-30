package com.pixflow.agent.skill;

/**
 * SKILL.md 解析失败的标记异常。
 *
 * <p>启动期 {@code SkillLoader.syncBuiltIn} 遇此异常会记 WARN + 指标，不抛出
 * （保证启动不被一个坏 skill 卡死）。
 */
public class SkillParseException extends RuntimeException {

    public SkillParseException(String message) {
        super(message);
    }

    public SkillParseException(String message, Throwable cause) {
        super(message, cause);
    }
}