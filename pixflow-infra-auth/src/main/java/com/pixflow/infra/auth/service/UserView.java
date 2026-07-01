package com.pixflow.infra.auth.service;

import com.pixflow.infra.auth.context.AuthPrincipal;

public record UserView(Long userId, String username, String displayName, String status) {
    public static UserView from(AuthPrincipal principal) {
        return new UserView(principal.userId(), principal.username(), principal.displayName(), principal.status());
    }
}
