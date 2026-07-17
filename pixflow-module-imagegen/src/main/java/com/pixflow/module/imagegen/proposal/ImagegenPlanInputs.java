package com.pixflow.module.imagegen.proposal;

import java.util.Map;

/**
 * submit_imagegen_plan 工具的浅层入参形状(对齐 imagegen.md §5.1)。
 *
 * <p>浅层:只承担 JSON 反序列化后的形状;真实校验在 {@link ImagegenPlanValidator} 完成。
 * 字段命名与 App 暴露的工具 JSON 合同一致。
 */
public record ImagegenPlanInputs(
        String referenceKey,
        String prompt,
        String note,
        Map<String, Object> params) {
}
