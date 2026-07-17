package com.pixflow.harness.permission.proof;

import com.pixflow.harness.permission.PermissionPrincipal;

public interface ConversationAuthorizationPort {
    ProofResult proveAccess(PermissionPrincipal principal, String conversationId);
}
