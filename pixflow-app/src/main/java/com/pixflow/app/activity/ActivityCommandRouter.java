package com.pixflow.app.activity;

import com.pixflow.infra.auth.context.AuthPrincipal;

public interface ActivityCommandRouter {
    void cancel(ActivityView activity, AuthPrincipal principal);

    void clear(ActivityView activity, AuthPrincipal principal);
}
