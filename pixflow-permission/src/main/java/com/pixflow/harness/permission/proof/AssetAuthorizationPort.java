package com.pixflow.harness.permission.proof;

import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;

public interface AssetAuthorizationPort {
    ProofResult proveAccess(
            PermissionPrincipal principal, String referenceKey, AssetAccessMode mode);
}
