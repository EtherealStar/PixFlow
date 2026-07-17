package com.pixflow.app.permission;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.TaskCommandType;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.Objects;

/** Task 属主事实适配器；管理员资格和会话属主已由策略在此证明之前检查。 */
public final class TaskPermissionProof implements TaskAuthorizationPort {
    private final ProcessTaskMapper taskMapper;

    public TaskPermissionProof(ProcessTaskMapper taskMapper) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
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
            ProcessTask task = taskMapper.findByIdAndConversation(Long.parseLong(taskId), conversationId);
            return task != null && commandAllowed(task.getStatus(), command)
                    ? ProofResult.PROVED : ProofResult.DENIED;
        } catch (NumberFormatException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    private static boolean commandAllowed(TaskStatus status, TaskCommandType command) {
        if (status == null) {
            return false;
        }
        return switch (command) {
            case CANCEL -> !status.terminal();
            case RETRY -> status == TaskStatus.FAILED || status == TaskStatus.PARTIAL;
            case DELETE -> status.terminal();
            case DOWNLOAD -> status == TaskStatus.COMPLETED || status == TaskStatus.PARTIAL;
            case CONFIRM_REPLAY -> true;
        };
    }
}
