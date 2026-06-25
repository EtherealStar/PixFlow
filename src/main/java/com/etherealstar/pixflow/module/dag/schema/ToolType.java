package com.etherealstar.pixflow.module.dag.schema;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具白名单枚举（需求 7.4、设计「工具参数 Schema」）。
 *
 * <p>这是工具白名单的唯一权威来源：DAG_Parser 解析、DAG_Validator 校验工具名合法性、
 * 以及参数 schema 注册 {@link ToolSchemaRegistry} 均以此枚举为准。每个枚举常量携带其在
 * DAG JSON 中使用的工具名（wire name），如 {@code remove_bg}。
 */
public enum ToolType {

    /** 去背景：无参数。 */
    REMOVE_BG("remove_bg"),

    /** 设置背景色：可选 {@code color}（默认 {@code #FFFFFF}）。 */
    SET_BACKGROUND("set_background"),

    /** 缩放：必填 {@code width}(>0)、{@code height}(>0)。 */
    RESIZE("resize"),

    /** 压缩：必填 {@code max_kb}(>0)。 */
    COMPRESS("compress"),

    /** 水印：必填 {@code position}（枚举），{@code text} 或 {@code image} 二选一。 */
    WATERMARK("watermark"),

    /** 格式转换：必填 {@code format}（JPG/PNG/WebP）。 */
    CONVERT_FORMAT("convert_format"),

    /** 文案生成：可选 {@code style}。 */
    GENERATE_COPY("generate_copy");

    private final String toolName;

    ToolType(String toolName) {
        this.toolName = toolName;
    }

    /** DAG JSON 中节点 {@code tool} 字段使用的工具名。 */
    public String getToolName() {
        return toolName;
    }

    /**
     * 依据 DAG JSON 中的工具名查找对应枚举（大小写敏感，工具名约定为小写下划线形式）。
     *
     * @param toolName 节点 {@code tool} 字段值
     * @return 匹配的 {@link ToolType}，不存在则为空
     */
    public static Optional<ToolType> fromToolName(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(t -> t.toolName.equals(toolName))
                .findFirst();
    }

    /** 工具名白名单集合，供校验与提示词构造使用。 */
    public static Set<String> whitelist() {
        return Arrays.stream(values())
                .map(ToolType::getToolName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
