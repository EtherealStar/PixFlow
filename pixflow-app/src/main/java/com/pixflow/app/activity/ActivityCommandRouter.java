package com.pixflow.app.activity;

import com.pixflow.infra.auth.context.AuthPrincipal;

public interface ActivityCommandRouter {
    void cancel(ActivityCommandTarget target, AuthPrincipal principal);

    ActivityRetryResult retryFailed(ActivityCommandTarget target, AuthPrincipal principal);

    void clear(ActivityCommandTarget target, AuthPrincipal principal);
}
