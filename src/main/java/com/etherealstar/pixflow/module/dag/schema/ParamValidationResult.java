package com.etherealstar.pixflow.module.dag.schema;

import java.util.List;

/**
 * 单个工具节点参数校验结果（需求 7.5）。
 *
 * <p>{@code valid} 为 true 时 {@code errors} 为空；为 false 时 {@code errors} 列出全部不满足
 * schema 的原因（不含节点 id，节点 id 由 DAG_Validator 在包装错误时补充）。
 *
 * @param valid  是否通过校验
 * @param errors 不通过时的错误描述列表（已不可变）
 */
public record ParamValidationResult(boolean valid, List<String> errors) {

    public ParamValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    private static final ParamValidationResult VALID = new ParamValidationResult(true, List.of());

    /**
     * 校验通过。
     *
     * <p>注意：方法名不可为 {@code valid()}，因为记录组件 {@code valid} 已生成同名实例访问器，
     * 二者会发生签名冲突，故工厂方法命名为 {@code passed()}。</p>
     */
    public static ParamValidationResult passed() {
        return VALID;
    }

    /** 校验失败，携带错误原因列表。 */
    public static ParamValidationResult invalid(List<String> errors) {
        return new ParamValidationResult(false, errors);
    }
}
