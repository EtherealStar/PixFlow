package com.pixflow.infra.auth.identity;

import com.pixflow.infra.auth.context.AuthPrincipal;

/** 每次认证边界都通过此接口重新读取 Configured Administrator 的当前资格。 */
public interface AdministratorEligibility {
    AuthPrincipal current();

    AuthPrincipal requireEligible(long userId);
}
