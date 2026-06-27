package com.pixflow.harness.permission;

import com.pixflow.contracts.confirmation.ConfirmationAction;
import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.harness.permission.subagent.SubagentConstraint;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 默认 deny-first 权限策略。
 */
public class DefaultPermissionPolicy implements PermissionPolicy {
    private final ConfirmationTokenService confirmationTokenService;
    private final int bulkThreshold;

    public DefaultPermissionPolicy(ConfirmationTokenService confirmationTokenService, int bulkThreshold) {
        this.confirmationTokenService = Objects.requireNonNull(confirmationTokenService, "confirmationTokenService");
        if (bulkThreshold < 1) {
            throw new IllegalArgumentException("bulkThreshold 必须大于 0");
        }
        this.bulkThreshold = bulkThreshold;
    }

    @Override
    public PermissionDecision evaluate(PermissionSubject subject, PermissionContext context) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(context, "context");

        PermissionDecision subagentDecision = evaluateSubagentConstraint(subject, context);
        if (subagentDecision != null) {
            return subagentDecision;
        }

        PermissionDecision toolDecision = evaluateToolAvailability(subject, context);
        if (toolDecision != null) {
            return toolDecision;
        }

        if (subject.confirmationAction() == null) {
            return PermissionDecision.allow(subject.toolName(), PermissionSource.DEFAULT_ALLOW);
        }

        ConfirmationToken pendingToken = context.pendingToken();
        if (pendingToken == null) {
            return confirmRequired(subject);
        }

        try {
            confirmationTokenService.verifyAndConsume(pendingToken, subject, context, bulkThreshold);
            return PermissionDecision.allow(subject.toolName(), PermissionSource.TOKEN_GATE);
        } catch (com.pixflow.common.error.PixFlowException exception) {
            return deniedByToken(subject, exception);
        }
    }

    @Override
    public boolean isToolVisible(String toolName, PermissionContext context) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(context, "context");

        if (context.deniedTools().contains(toolName) || context.disabledTools().contains(toolName)) {
            return false;
        }

        SubagentConstraint subagent = context.subagent();
        if (subagent == null) {
            return true;
        }
        if (subagent.disallowedTools().contains(toolName)) {
            return false;
        }
        Set<String> allowedTools = subagent.allowedTools();
        return allowedTools.isEmpty() || allowedTools.contains("*") || allowedTools.contains(toolName);
    }

    private PermissionDecision evaluateSubagentConstraint(PermissionSubject subject, PermissionContext context) {
        SubagentConstraint subagent = context.subagent();
        if (subagent == null) {
            return null;
        }
        if (subagent.readOnly() && !subject.readOnly()) {
            return PermissionDecision.deny(
                    subject.toolName(),
                    PermissionSource.SUBAGENT_CONSTRAINT,
                    "只读子 Agent 不能执行副作用动作");
        }
        if (!isToolVisible(subject.toolName(), context)) {
            return PermissionDecision.deny(
                    subject.toolName(),
                    PermissionSource.SUBAGENT_CONSTRAINT,
                    "当前子 Agent 不允许访问该工具");
        }
        return null;
    }

    private PermissionDecision evaluateToolAvailability(PermissionSubject subject, PermissionContext context) {
        if (context.deniedTools().contains(subject.toolName())) {
            return PermissionDecision.deny(
                    subject.toolName(), PermissionSource.TOOL_DENIED, "工具被显式拒绝");
        }
        if (context.disabledTools().contains(subject.toolName())) {
            return PermissionDecision.deny(
                    subject.toolName(), PermissionSource.TOOL_DISABLED, "工具已禁用");
        }
        return null;
    }

    private PermissionDecision confirmRequired(PermissionSubject subject) {
        ConfirmationAction action = subject.confirmationAction();
        int requiredLevel = subject.actualCount() > bulkThreshold ? 1 : 0;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requiredAction", action.name());
        metadata.put("requiredLevel", requiredLevel == 1 ? "BULK" : "NORMAL");
        metadata.put("actualCount", subject.actualCount());
        metadata.put("bulkThreshold", bulkThreshold);
        metadata.put("confirmReason", requiredLevel == 1 ? "超过批量阈值，需要二次确认" : "需要用户确认后继续执行");
        return PermissionDecision.confirmRequired(
                subject.toolName(),
                PermissionSource.TOKEN_GATE,
                requiredLevel == 1 ? "需要批量确认" : "需要确认",
                metadata);
    }

    private PermissionDecision deniedByToken(PermissionSubject subject, com.pixflow.common.error.PixFlowException exception) {
        return PermissionDecision.deny(subject.toolName(), PermissionSource.TOKEN_GATE, exception.getMessage());
    }
}
