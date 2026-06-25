package com.etherealstar.pixflow.module.dag.schema;

import java.util.Optional;

/**
 * 单个参数取值校验器（需求 7.5）。
 *
 * <p>仅在参数实际出现时被调用，对其取值进行类型与约束校验。返回 {@link Optional#empty()}
 * 表示取值合法；返回携带描述的 {@code Optional} 表示取值非法。
 */
@FunctionalInterface
public interface ParamValueValidator {

    /**
     * 校验某参数的取值。
     *
     * @param paramName 参数名（用于构造可读的错误信息）
     * @param value     参数取值（来自 DAG JSON 解析后的 {@code params} map，可能为 Number/String 等）
     * @return 非法时返回错误描述，合法时返回空
     */
    Optional<String> validate(String paramName, Object value);
}
