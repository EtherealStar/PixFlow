package com.etherealstar.pixflow.infra.ai;

import org.springframework.stereotype.Component;

/**
 * DAG 解析提示词管理器。
 *
 * <p>负责构造交给 LLM 的系统提示词，在提示词中<strong>固定工具白名单</strong>与<strong>各工具的参数 schema</strong>，
 * 约束模型只能输出白名单内的工具节点与其允许的参数，并规定输出为可解析的 DAG JSON 结构。
 * 工具白名单与 schema 来自 {@link ToolCatalog}（单一权威来源）。</p>
 *
 * <p>对应需求 6.1：调用 LLM 将自然语言指令解析为 DAG JSON，且解析出的节点 {@code tool} 仅限工具白名单内取值。</p>
 */
@Component
public class DagPromptManager {

    /**
     * 构造 DAG 解析的系统提示词，固定工具白名单与各工具参数 schema 及输出格式约束。
     *
     * @return 系统提示词文本
     */
    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 PixFlow 的图片批处理指令解析器。你的任务是把电商运营人员的一句自然语言指令，"
            + "解析为一个描述工具编排的 DAG（有向无环图）JSON。\n\n");

        sb.append("# 硬性约束\n");
        sb.append("1. 只能使用下方「工具白名单」中列出的工具，禁止虚构或使用白名单之外的任何工具名。\n");
        sb.append("2. 每个节点的 params 只能包含对应工具 schema 中定义的参数，参数取值必须满足其约束。\n");
        sb.append("3. 不要猜测或自行填充缺失的必填参数；缺失的必填参数留空，由系统向用户追问。\n");
        sb.append("4. 当指令同时涉及 convert_format（格式转换）与 compress（压缩至目标体积）时，"
            + "必须固定为「先 convert_format 后 compress」的顺序，即先用一条边由 convert_format 指向 compress，"
            + "以保证相同指令始终产出一致的先后顺序。\n");
        sb.append("5. 仅输出 JSON 本身，不要包含任何解释性文字或 Markdown 代码块标记。\n\n");

        sb.append("# 工具白名单与参数 schema\n");
        appendToolSchemas(sb);

        sb.append("\n# 输出格式\n");
        sb.append("严格输出如下结构的 JSON：\n");
        sb.append("{\n");
        sb.append("  \"nodes\": [\n");
        sb.append("    { \"id\": \"n1\", \"tool\": \"<白名单内工具名>\", \"params\": { } }\n");
        sb.append("  ],\n");
        sb.append("  \"edges\": [\n");
        sb.append("    { \"from\": \"n1\", \"to\": \"n2\" }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("其中：每个节点的 id 在 DAG 内唯一；edges 描述节点间依赖（from 先于 to 执行）；"
            + "DAG 不得成环；节点总数为 1–50。\n");

        return sb.toString();
    }

    /**
     * 将用户的自然语言指令包装为用户提示词。
     *
     * @param instruction 用户的自然语言处理指令
     * @return 用户提示词文本
     */
    public String buildUserPrompt(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("指令内容不能为空");
        }
        return "请将以下指令解析为 DAG JSON：\n" + instruction.trim();
    }

    private void appendToolSchemas(StringBuilder sb) {
        for (ToolDefinition def : ToolCatalog.definitions()) {
            sb.append("- ").append(def.tool());
            if (!def.description().isEmpty()) {
                sb.append("：").append(def.description());
            }
            sb.append('\n');
            if (def.params().isEmpty()) {
                sb.append("    无参数\n");
            } else {
                for (ToolParam p : def.params()) {
                    sb.append("    · ").append(p.name())
                        .append(" (").append(p.type()).append(", ")
                        .append(p.required() ? "必填" : "可选").append(")");
                    if (!p.constraint().isEmpty()) {
                        sb.append("：").append(p.constraint());
                    }
                    sb.append('\n');
                }
            }
            for (String note : def.notes()) {
                sb.append("    注意：").append(note).append('\n');
            }
        }
    }
}
