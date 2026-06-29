package com.pixflow.module.dag.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DAG 校验结果(逐项错误聚合,不抛异常)。
 *
 * <p>校验器把所有失败原因装入 errors 列表,调用方(SubmitImagePlanHandler / 确认 REST 边界)
 * 据 errors 与 ok() 决定下一步(入队 / 拒绝 / 二次确认 HITL)。
 *
 * <p>errors 元素是不可变字符串(错误码 + 描述),便于直接进 tool error 的 message。
 */
public record DagValidationResult(boolean ok, List<String> errors, Map<String, Object> details) {

    public DagValidationResult {
        errors = List.copyOf(errors);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static DagValidationResult success() {
        return new DagValidationResult(true, List.of(), Map.of());
    }

    public static DagValidationResult failure(List<String> errors, Map<String, Object> details) {
        return new DagValidationResult(false, List.copyOf(errors), details == null ? Map.of() : details);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 流式构造器:链式 push 错误后 build。 */
    public static final class Builder {
        private final List<String> errors = new ArrayList<>();
        private final java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();

        public Builder add(String code, String message) {
            errors.add(code + ": " + message);
            return this;
        }

        public Builder add(String code, String message, Map<String, Object> detail) {
            add(code, message);
            if (detail != null) {
                details.put(code, detail);
            }
            return this;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<String> errorsView() {
            return Collections.unmodifiableList(errors);
        }

        public DagValidationResult build() {
            return new DagValidationResult(errors.isEmpty(),
                Collections.unmodifiableList(new ArrayList<>(errors)),
                Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details)));
        }
    }
}