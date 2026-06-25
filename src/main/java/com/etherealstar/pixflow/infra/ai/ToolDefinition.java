package com.etherealstar.pixflow.infra.ai;

import java.util.List;

/**
 * 单个工具节点的完整定义：工具名、用途描述、参数列表与跨参数约束说明。
 *
 * <p>作为工具白名单与参数 schema 的结构化载体，既用于在提示词中向 LLM 固定可用工具及其参数，
 * 后续也可被 DAG_Validator 复用做参数校验。</p>
 *
 * @param tool        工具名（白名单内取值）
 * @param description 工具用途的简短描述
 * @param params      参数定义列表（含必填与可选）
 * @param notes       跨参数的特殊约束说明（如「text 与 image 二选一」），无则为空列表
 */
public record ToolDefinition(String tool, String description, List<ToolParam> params, List<String> notes) {

    public ToolDefinition {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        description = description == null ? "" : description;
        params = params == null ? List.of() : List.copyOf(params);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 该工具的必填参数名列表。 */
    public List<String> requiredParamNames() {
        return params.stream().filter(ToolParam::required).map(ToolParam::name).toList();
    }
}
