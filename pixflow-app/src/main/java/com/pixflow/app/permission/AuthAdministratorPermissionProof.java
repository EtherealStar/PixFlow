package com.pixflow.app.permission;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.infra.auth.identity.AdministratorIneligibleException;
import java.util.Objects;

/** 将 Auth 的当前管理员资格翻译为 Permission proof。 */
public final class AuthAdministratorPermissionProof implements AdministratorEligibilityPort {
    private final AdministratorEligibility eligibility;

    public AuthAdministratorPermissionProof(AdministratorEligibility eligibility) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }

    @Override
    public ProofResult verify(PermissionPrincipal principal) {
        if (principal == null) {
            return ProofResult.DENIED;
        }
        try {
            long userId = Long.parseLong(principal.userId());
            AuthPrincipal current = eligibility.requireEligible(userId);
            return userId == current.userId() && principal.username().equals(current.username())
                    ? ProofResult.PROVED : ProofResult.DENIED;
        } catch (AdministratorIneligibleException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            // 管理员事实源异常时必须 fail closed，不能沿用请求中携带的旧身份。
            return ProofResult.UNAVAILABLE;
        }
    }
}
