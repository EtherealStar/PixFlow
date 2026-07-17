package com.pixflow.harness.permission.proof;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.TaskCommandType;

public interface TaskAuthorizationPort {
    ProofResult proveCommand(
            PermissionPrincipal principal,
            String conversationId,
            String taskId,
            TaskCommandType command);
}
