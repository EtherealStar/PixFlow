package com.pixflow.module.dag.exec;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CopyUnitExecutor:文案支路执行器(对齐 dag.md §9)。
 *
 * <p>不在像素链上;调 {@link ChatModelClient#call(ChatRequest)} 生成文案,
 * 产出 UnitOutcome.generatedCopy。
 */
@Component
public class CopyUnitExecutor implements UnitExecutor {

    private final ChatModelClient chatModelClient;

    public CopyUnitExecutor(ChatModelClient chatModelClient) {
        this.chatModelClient = chatModelClient;
    }

    @Override
    public UnitOutcome execute(ExecutableBranch branch, UnitInput input) {
        if (branch == null || input == null || input.copyContext() == null) {
            return UnitOutcome.failed(UnitKind.BRANCH, null, null,
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "文案支路缺 CopyContext",
                    ErrorCategory.VALIDATION));
        }
        try {
            CopyStep copyNode = branch.perMemberOps().stream()
                .filter(CopyStep.class::isInstance)
                .map(CopyStep.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("支路不含 generate_copy"));
            String prompt = buildPrompt(input.copyContext(), copyNode.typedSpec());
            ChatRequest req = new ChatRequest(
                ModelRole.PRIMARY_CHAT,
                List.of(new ChatMessage(ChatMessage.Role.USER,
                    List.of(new ChatMessage.TextPart(prompt)))),
                List.of(),
                com.pixflow.infra.ai.chat.ToolChoice.AUTO,
                null,
                null
            );
            ChatResult result = chatModelClient.call(req);
            String copy = result.finalText() == null ? "" : result.finalText().trim();
            if (copy.isEmpty()) {
                return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                    new UnitOutcome.DagErrorView(DagErrorCode.DAG_UNIT_EXECUTION_FAILED,
                        "ChatModelClient 返回空文案",
                        ErrorCategory.PROVIDER));
            }
            // 抑制未使用变量告警
            if (result.toolCalls() != null) {
                for (ToolCall tc : result.toolCalls()) {
                    // no-op
                }
            }
            return UnitOutcome.copySucceeded(branch.kind(), branch.branchId(),
                branch.memberId(), copy);
        } catch (Throwable t) {
            PixFlowException pe = normalize(t);
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(
                    pe.code() instanceof DagErrorCode dec ? dec : DagErrorCode.DAG_UNIT_EXECUTION_FAILED,
                    Sanitizer.sanitizeMessage(pe.getMessage()),
                    pe.category()));
        }
    }

    private String buildPrompt(CopyContext ctx, CopyBindingSpec spec) {
        String style = spec.style();
        String language = spec.language();
        int maxLen = spec.maxLength();
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下商品生成 ").append(style).append(" 风格的电商文案(语言:").append(language)
          .append(",最长 ").append(maxLen).append(" 字):\n");
        if (ctx.productName() != null) {
            sb.append("商品名称:").append(ctx.productName()).append("\n");
        }
        if (ctx.description() != null) {
            sb.append("描述:").append(ctx.description()).append("\n");
        }
        if (ctx.keywords() != null && !ctx.keywords().isEmpty()) {
            sb.append("关键词:").append(String.join(",", ctx.keywords())).append("\n");
        }
        return sb.toString();
    }

    private PixFlowException normalize(Throwable t) {
        if (t instanceof PixFlowException pe) {
            return pe;
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return new PixFlowException(DagErrorCode.DAG_UNIT_EXECUTION_FAILED,
            Sanitizer.sanitizeMessage(msg), t,
            Map.of("category", ErrorCategory.PROVIDER));
    }
}
