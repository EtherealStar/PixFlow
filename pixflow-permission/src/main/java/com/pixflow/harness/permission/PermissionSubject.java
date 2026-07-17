package com.pixflow.harness.permission;

import java.util.List;
import java.util.Map;

/** 权限边界能够识别的动作类型。 */
public sealed interface PermissionSubject {

    record ToolInvocation(String toolName, boolean readOnly, Map<String, Object> safeFacts)
            implements PermissionSubject {
        public ToolInvocation {
            toolName = PermissionValues.requireText(toolName, "toolName");
            safeFacts = PermissionValues.copySafeFacts(safeFacts);
        }
    }

    record AssetAccess(String referenceKey, AssetAccessMode mode) implements PermissionSubject {
        public AssetAccess {
            referenceKey = PermissionValues.requireCanonicalReference(referenceKey);
            mode = PermissionValues.requireNonNull(mode, "mode");
        }
    }

    record ProposalPublication(
            String proposalType, List<String> referenceKeys, String payloadHash)
            implements PermissionSubject {
        public ProposalPublication {
            proposalType = PermissionValues.requireText(proposalType, "proposalType");
            referenceKeys = PermissionValues.copyCanonicalReferences(referenceKeys);
            payloadHash = PermissionValues.requireText(payloadHash, "payloadHash");
        }
    }

    record ProposalConfirmation(
            String proposalId, List<String> referenceKeys, String payloadHash)
            implements PermissionSubject {
        public ProposalConfirmation {
            proposalId = PermissionValues.requireText(proposalId, "proposalId");
            referenceKeys = PermissionValues.copyCanonicalReferences(referenceKeys);
            payloadHash = PermissionValues.requireText(payloadHash, "payloadHash");
        }
    }

    record TaskCommand(String taskId, TaskCommandType command) implements PermissionSubject {
        public TaskCommand {
            taskId = PermissionValues.requireText(taskId, "taskId");
            command = PermissionValues.requireNonNull(command, "command");
        }
    }
}
