package com.pixflow.app.permission;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.TaskCommandType;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFacts;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFactsQuery;
import java.util.Objects;

/** Task 属主事实适配器；管理员资格和会话属主已由策略在此证明之前检查。 */
public final class TaskPermissionProof implements TaskAuthorizationPort {
    private final TaskAuthorizationFactsQuery factsQuery;

    public TaskPermissionProof(TaskAuthorizationFactsQuery factsQuery) {
        this.factsQuery = Objects.requireNonNull(factsQuery, "factsQuery");
    }

    @Override
    public ProofResult proveCommand(
            PermissionPrincipal principal,
            String conversationId,
            String taskId,
            TaskCommandType command) {
        if (principal == null || command == null || conversationId == null || conversationId.isBlank()) {
            return ProofResult.DENIED;
        }
        try {
            return factsQuery.find(taskId)
                    .filter(facts -> conversationId.equals(facts.conversationId()))
                    .filter(facts -> commandAllowed(facts, command))
                    .map(ignored -> ProofResult.PROVED)
                    .orElse(ProofResult.DENIED);
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    private static boolean commandAllowed(TaskAuthorizationFacts facts, TaskCommandType command) {
        return switch (command) {
            case CANCEL -> facts.cancellable();
            case RETRY -> facts.retryable();
            case DELETE -> facts.deletable();
            case DOWNLOAD -> facts.downloadable();
            case CONFIRM_REPLAY -> true;
        };
    }
}
