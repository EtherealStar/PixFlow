package com.pixflow.harness.permission.proof;

import com.pixflow.harness.permission.PermissionPrincipal;

public interface AdministratorEligibilityPort {
    ProofResult verify(PermissionPrincipal principal);
}
