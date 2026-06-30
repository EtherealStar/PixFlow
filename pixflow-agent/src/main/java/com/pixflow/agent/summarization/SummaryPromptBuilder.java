package com.pixflow.agent.summarization;

import com.pixflow.harness.context.compaction.SummarizationPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摘要 child prompt 构造器。
 *
 * <p>对应 agent.md §9.3：
 * 构造 child 任务指令（含 summaryInstructions + 待摘要 messages + 输出要求）。
 */
@Component
public class SummaryPromptBuilder {

    /**
     * 构造 child 摘要任务 prompt。
     */
    public String build(SummarizationPort.SummarizationRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 摘要任务\n\n");
        List<String> instructions = req.summaryInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("## 上下文\n");
            sb.append(String.join("\n", instructions));
            sb.append("\n\n");
        }
        sb.append("## 待摘要对话\n");
        if (req.messages() == null || req.messages().isEmpty()) {
            sb.append("(空)\n\n");
        } else {
            for (Object msg : req.messages()) {
                sb.append("- ").append(String.valueOf(msg)).append('\n');
            }
            sb.append('\n');
        }
        String focus = req.focus();
        if (focus != null && !focus.isBlank()) {
            sb.append("## 聚焦\n").append(focus).append("\n\n");
        }
        sb.append("## 输出要求\n");
        sb.append("总结上述对话为简洁 Markdown 文本（≤ 5K token），保留：\n");
        sb.append("- 关键决策（已确认/已拒绝的方案）\n");
        sb.append("- 当前任务状态（进行中/待办）\n");
        sb.append("- 关键数据点（SKU ID、电商指标、参数）\n");
        sb.append("- 用户偏好更新\n\n");
        sb.append("删除：\n");
        sb.append("- 临时推理步骤\n");
        sb.append("- 重复确认的同类信息\n");
        sb.append("- 已撤回/被否的方案细节\n\n");
        sb.append("输出纯 Markdown 文本，不包含\"以下是摘要\"等元说明。");
        return sb.toString();
    }
}