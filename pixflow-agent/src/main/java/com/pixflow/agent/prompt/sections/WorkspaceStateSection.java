package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.stereotype.Component;

/**
 * 动态 section 9：工作区状态（workspace_state）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 9 行；
 * 渲染当前素材包 / 会话上下文 / Plan 模式标记 + 草拟计划（如有）。
 *
 * <p>fingerprint = "{packageId}.{turnCount}.{planMode}"。
 */
@Component
public final class WorkspaceStateSection implements SectionRenderer {

    @Override
    public String key() {
        return "workspace_state";
    }

    @Override
    public String title() {
        return "工作区状态";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        StringBuilder body = new StringBuilder();
        if (ctx.packageId() != null && !ctx.packageId().isBlank()) {
            body.append("- 当前素材包：").append(ctx.packageId()).append('\n');
        } else {
            body.append("- 当前素材包：（无）\n");
        }
        body.append("- 回合号：").append(ctx.turnNo() == null ? 0 : ctx.turnNo()).append('\n');
        body.append("- Plan 模式：").append(ctx.planMode()).append('\n');
        if (ctx.attachedSkuIds() != null && !ctx.attachedSkuIds().isEmpty()) {
            body.append("- 当前素材包 SKU：")
                    .append(String.join(", ", ctx.attachedSkuIds()))
                    .append('\n');
        }
        if (ctx.mentionedSkuIds() != null && !ctx.mentionedSkuIds().isEmpty()) {
            body.append("- 最近提及 SKU：")
                    .append(String.join(", ", ctx.mentionedSkuIds()))
                    .append('\n');
        }
        // 草拟计划（如有）
        Object draft = ctx.state() == null ? null : ctx.state().metadata().get("lastPlanDraft");
        if (draft instanceof String s && !s.isBlank()) {
            body.append("\n### 上次退出 Plan 模式时的草拟计划\n");
            body.append(s).append('\n');
        }
        String text = body.toString().strip();
        String packageId = ctx.packageId() == null ? "" : ctx.packageId();
        int turn = ctx.turnNo() == null ? 0 : ctx.turnNo();
        String fingerprint = packageId + "." + turn + "." + ctx.planMode();
        return new PromptSection(key(), title(), text, fingerprint, true);
    }
}