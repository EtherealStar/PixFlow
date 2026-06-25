package com.etherealstar.pixflow.infra.ai;

/**
 * 工具节点的单个参数定义。
 *
 * @param name       参数名（与 DAG JSON 中 {@code params} 的键一致）
 * @param type       参数类型描述（如 {@code string}、{@code integer}、{@code enum}）
 * @param required   是否为必填参数
 * @param constraint 取值约束的人类可读描述（如 {@code >0}、默认值、枚举取值范围）；无约束可为空字符串
 */
public record ToolParam(String name, String type, boolean required, String constraint) {

    public ToolParam {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("参数名不能为空");
        }
        type = type == null ? "" : type;
        constraint = constraint == null ? "" : constraint;
    }

    /** 构造一个必填参数。 */
    public static ToolParam required(String name, String type, String constraint) {
        return new ToolParam(name, type, true, constraint);
    }

    /** 构造一个可选参数。 */
    public static ToolParam optional(String name, String type, String constraint) {
        return new ToolParam(name, type, false, constraint);
    }
}
