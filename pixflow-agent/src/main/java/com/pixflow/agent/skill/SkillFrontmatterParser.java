package com.pixflow.agent.skill;

import com.pixflow.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 简单 SKILL.md frontmatter 解析器（不引 SnakeYAML）。
 *
 * <p>对应 {@code agent.md §5.3}：
 * <ul>
 *   <li>frontmatter 在 {@code ---} 之间</li>
 *   <li>key: value 格式（4 字段强制：name / description / when_to_use / version）</li>
 *   <li>校验：name 正则、description 长度、when_to_use 长度、version 正整数</li>
 * </ul>
 */
@Component
public final class SkillFrontmatterParser {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,40}$");

    private static final Pattern VERSION_PATTERN = Pattern.compile("^[1-9]\\d*$");

    private final AgentProperties props;

    public SkillFrontmatterParser(AgentProperties props) {
        this.props = props;
    }

    /**
     * 解析 + 校验。
     *
     * @param content 完整 SKILL.md 文件内容
     * @return 解析后的 (frontmatter, body)
     * @throws SkillParseException 格式不合法时
     */
    public ParsedSkill parse(String content) {
        if (content == null) {
            throw new SkillParseException("Content is null");
        }
        int firstDash = content.indexOf("---");
        if (firstDash != 0) {
            throw new SkillParseException("SKILL.md 必须以 '---' 开头（frontmatter 分隔符）");
        }
        int secondDash = content.indexOf("---", 3);
        if (secondDash < 0) {
            throw new SkillParseException("SKILL.md 缺少 frontmatter 结束分隔符 '---'");
        }
        String frontmatter = content.substring(3, secondDash).strip();
        String body = content.substring(secondDash + 3).strip();

        // 解析 key: value 行
        String name = null;
        String description = null;
        String whenToUse = null;
        int version = -1;
        for (String line : frontmatter.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                throw new SkillParseException("frontmatter 行缺少冒号: " + trimmed);
            }
            String key = trimmed.substring(0, colon).strip();
            String value = unquote(trimmed.substring(colon + 1).strip());
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "when_to_use" -> whenToUse = value;
                case "version" -> {
                    if (!VERSION_PATTERN.matcher(value).matches()) {
                        throw new SkillParseException("version 必须是正整数: " + value);
                    }
                    version = Integer.parseInt(value);
                }
                default -> { /* 未知字段忽略（向前兼容） */ }
            }
        }
        if (name == null) {
            throw new SkillParseException("frontmatter 缺少 name 字段");
        }
        if (description == null) {
            throw new SkillParseException("frontmatter 缺少 description 字段");
        }
        if (whenToUse == null) {
            throw new SkillParseException("frontmatter 缺少 when_to_use 字段");
        }
        if (version < 0) {
            throw new SkillParseException("frontmatter 缺少 version 字段");
        }

        // 校验
        validate(name, description, whenToUse, version, body);

        return new ParsedSkill(new SkillFrontmatter(name, description, whenToUse, version), body);
    }

    private void validate(String name, String description, String whenToUse, int version, String body) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new SkillParseException(
                    "name 必须匹配 ^[a-z][a-z0-9_]{1,40}$（snake_case，1-41 字符）: " + name);
        }
        // 与 7 个固定工具名 / 命名空间冲突检测
        if (name.startsWith("skill__")) {
            throw new SkillParseException("name 不能以 'skill__' 开头（命名空间冲突）: " + name);
        }
        for (String reserved : List.of("search", "read", "agent",
                "submit_image_plan", "submit_imagegen_plan", "plan", "plan_exit")) {
            if (name.equals(reserved)) {
                throw new SkillParseException("name 与固定工具冲突: " + name);
            }
        }
        if (description.length() > props.getSkill().getDescriptionMaxChars()) {
            throw new SkillParseException(
                    "description 长度超过 " + props.getSkill().getDescriptionMaxChars() + ": " + name);
        }
        if (whenToUse.length() > props.getSkill().getWhenToUseMaxChars()) {
            throw new SkillParseException(
                    "when_to_use 长度超过 " + props.getSkill().getWhenToUseMaxChars() + ": " + name);
        }
        int bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes > props.getSkill().getMaxBodyBytes()) {
            throw new SkillParseException(
                    "body 字节数超过 " + props.getSkill().getMaxBodyBytes() + ": " + name);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                || value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 解析产物：frontmatter + body。
     */
    public record ParsedSkill(SkillFrontmatter frontmatter, String body) {
    }
}
