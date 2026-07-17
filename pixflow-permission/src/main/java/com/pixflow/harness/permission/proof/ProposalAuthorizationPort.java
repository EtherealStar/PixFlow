package com.pixflow.harness.permission.proof;

import com.pixflow.harness.permission.PermissionPrincipal;

public interface ProposalAuthorizationPort {
    ProofResult proveConfirmable(
            PermissionPrincipal principal,
            String conversationId,
            String proposalId,
            String payloadHash);
}
