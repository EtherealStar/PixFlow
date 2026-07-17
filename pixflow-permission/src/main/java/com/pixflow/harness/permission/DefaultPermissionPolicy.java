package com.pixflow.harness.permission;

import com.pixflow.harness.permission.PermissionSubject.AssetAccess;
import com.pixflow.harness.permission.PermissionSubject.ProposalConfirmation;
import com.pixflow.harness.permission.PermissionSubject.ProposalPublication;
import com.pixflow.harness.permission.PermissionSubject.TaskCommand;
import com.pixflow.harness.permission.PermissionSubject.ToolInvocation;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import java.util.Objects;
import java.util.function.Supplier;

/** 按固定顺序短路的 deny-first 策略。 */
public final class DefaultPermissionPolicy implements PermissionPolicy {
    private final AdministratorEligibilityPort administratorEligibility;

    private final ConversationAuthorizationPort conversationAuthorization;

    private final AssetAuthorizationPort assetAuthorization;

    private final ProposalAuthorizationPort proposalAuthorization;

    private final TaskAuthorizationPort taskAuthorization;

    public DefaultPermissionPolicy(
            AdministratorEligibilityPort administratorEligibility,
            ConversationAuthorizationPort conversationAuthorization,
            AssetAuthorizationPort assetAuthorization,
            ProposalAuthorizationPort proposalAuthorization,
            TaskAuthorizationPort taskAuthorization) {
        this.administratorEligibility = Objects.requireNonNull(
                administratorEligibility, "administratorEligibility");
        this.conversationAuthorization = Objects.requireNonNull(
                conversationAuthorization, "conversationAuthorization");
        this.assetAuthorization = Objects.requireNonNull(assetAuthorization, "assetAuthorization");
        this.proposalAuthorization = Objects.requireNonNull(
                proposalAuthorization, "proposalAuthorization");
        this.taskAuthorization = Objects.requireNonNull(taskAuthorization, "taskAuthorization");
    }

    @Override
    public PermissionDecision evaluate(PermissionContext context, PermissionSubject subject) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(subject, "subject");
        String name = subjectName(subject);

        if (context.principal() == null) {
            return deny(name, PermissionSource.AUTHENTICATION,
                    PermissionErrorCode.PERMISSION_UNAUTHENTICATED);
        }
        if (!proved(() -> administratorEligibility.verify(context.principal()))) {
            return deny(name, PermissionSource.ADMINISTRATOR,
                    PermissionErrorCode.PERMISSION_ADMIN_INELIGIBLE);
        }

        PermissionDecision runtimeDecision = evaluateRuntime(context, subject, name);
        if (runtimeDecision != null) {
            return runtimeDecision;
        }
        PermissionDecision planDecision = evaluatePlanMode(context, subject, name);
        if (planDecision != null) {
            return planDecision;
        }

        // Conversation 是所有业务动作的第一项资源证明；失败后绝不访问更深层资源。
        if (!proved(() -> conversationAuthorization.proveAccess(
                context.principal(), context.conversationId()))) {
            return deny(name, PermissionSource.CONVERSATION,
                    PermissionErrorCode.PERMISSION_CONVERSATION_DENIED);
        }

        if (subject instanceof AssetAccess asset) {
            return assetDecision(context, name, asset.referenceKey(), asset.mode());
        }
        if (subject instanceof ProposalPublication publication) {
            PermissionDecision referenceDecision = authorizeReferences(
                    context, name, publication.referenceKeys());
            if (referenceDecision != null) {
                return referenceDecision;
            }
        } else if (subject instanceof ProposalConfirmation confirmation) {
            // 确认时重新读取引用资源的当前事实，不能沿用发布时已经过期的授权结论。
            PermissionDecision referenceDecision = authorizeReferences(
                    context, name, confirmation.referenceKeys());
            if (referenceDecision != null) {
                return referenceDecision;
            }
            if (!proved(() -> proposalAuthorization.proveConfirmable(
                    context.principal(),
                    context.conversationId(),
                    confirmation.proposalId(),
                    confirmation.payloadHash()))) {
                return deny(name, PermissionSource.PROPOSAL,
                        PermissionErrorCode.PERMISSION_PROPOSAL_DENIED);
            }
        } else if (subject instanceof TaskCommand command) {
            if (!proved(() -> taskAuthorization.proveCommand(
                    context.principal(),
                    context.conversationId(),
                    command.taskId(),
                    command.command()))) {
                return deny(name, PermissionSource.TASK,
                        PermissionErrorCode.PERMISSION_TASK_DENIED);
            }
        }
        return PermissionDecision.allow(name);
    }

    private PermissionDecision authorizeReferences(
            PermissionContext context, String name, java.util.List<String> referenceKeys) {
        for (String referenceKey : referenceKeys) {
            PermissionDecision decision = assetDecision(
                    context, name, referenceKey, AssetAccessMode.PROCESS);
            if (decision.action() == PermissionAction.DENY) {
                return decision;
            }
        }
        return null;
    }

    @Override
    public boolean isToolVisible(
            String toolName, boolean readOnly, PermissionContext context) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(context, "context");
        if (context.principal() == null || context.runtimeScope() == null) {
            return false;
        }
        if (context.runtimeScope() == PermissionRuntimeScope.INTERNAL) {
            return false;
        }
        if (context.planMode() == PermissionPlanMode.ACTIVE) {
            return readOnly || "plan_exit".equals(toolName);
        }
        if (context.runtimeScope() == PermissionRuntimeScope.EXPLORE_CHILD) {
            return readOnly;
        }
        return true;
    }

    private PermissionDecision evaluateRuntime(
            PermissionContext context, PermissionSubject subject, String name) {
        if (context.runtimeScope() == null || context.runtimeScope() == PermissionRuntimeScope.INTERNAL) {
            return deny(name, PermissionSource.RUNTIME_SCOPE,
                    PermissionErrorCode.PERMISSION_SCOPE_DENIED);
        }
        if (context.runtimeScope() == PermissionRuntimeScope.EXPLORE_CHILD
                && (!(subject instanceof ToolInvocation tool) || !tool.readOnly())) {
            return deny(name, PermissionSource.RUNTIME_SCOPE,
                    PermissionErrorCode.PERMISSION_SCOPE_DENIED);
        }
        return null;
    }

    private PermissionDecision evaluatePlanMode(
            PermissionContext context, PermissionSubject subject, String name) {
        if (context.planMode() == null) {
            return deny(name, PermissionSource.PLAN_MODE,
                    PermissionErrorCode.PERMISSION_PLAN_MODE_DENIED);
        }
        if (context.planMode() != PermissionPlanMode.ACTIVE) {
            return null;
        }
        boolean permitted = subject instanceof ToolInvocation tool
                && (tool.readOnly() || "plan_exit".equals(tool.toolName()));
        return permitted ? null : deny(name, PermissionSource.PLAN_MODE,
                PermissionErrorCode.PERMISSION_PLAN_MODE_DENIED);
    }

    private PermissionDecision assetDecision(
            PermissionContext context,
            String name,
            String referenceKey,
            AssetAccessMode mode) {
        if (!proved(() -> assetAuthorization.proveAccess(
                context.principal(), referenceKey, mode))) {
            return deny(name, PermissionSource.ASSET, PermissionErrorCode.PERMISSION_ASSET_DENIED);
        }
        return PermissionDecision.allow(name);
    }

    private static boolean proved(Supplier<ProofResult> lookup) {
        try {
            return lookup.get() == ProofResult.PROVED;
        } catch (RuntimeException unavailable) {
            // 证明依赖异常不能降级为 allow，统一按 UNAVAILABLE fail closed。
            return false;
        }
    }

    private static PermissionDecision deny(
            String subject, PermissionSource source, PermissionErrorCode errorCode) {
        return PermissionDecision.deny(subject, source, errorCode);
    }

    private static String subjectName(PermissionSubject subject) {
        if (subject instanceof ToolInvocation tool) {
            return tool.toolName();
        }
        if (subject instanceof AssetAccess) {
            return "asset_access";
        }
        if (subject instanceof ProposalPublication) {
            return "proposal_publication";
        }
        if (subject instanceof ProposalConfirmation) {
            return "proposal_confirmation";
        }
        return "task_command";
    }

}
