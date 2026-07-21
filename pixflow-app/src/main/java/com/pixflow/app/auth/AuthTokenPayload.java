package com.pixflow.app.auth;

import com.pixflow.infra.auth.service.AuthTokenResponse;
import com.pixflow.infra.auth.service.UserView;
import com.pixflow.infra.auth.context.AuthPrincipal;
import java.time.Instant;

public record AuthTokenPayload(String accessToken, Instant accessTokenExpiresAt, AuthUserPayload user) {
    public static AuthTokenPayload from(AuthTokenResponse response) {
        return new AuthTokenPayload(
                response.accessToken(), response.accessTokenExpiresAt(), AuthUserPayload.from(response.user()));
    }

    public record AuthUserPayload(long userId, String username, String displayName) {
        public static AuthUserPayload from(UserView user) {
            return new AuthUserPayload(user.userId(), user.username(), user.displayName());
        }

        public static AuthUserPayload from(AuthPrincipal principal) {
            return new AuthUserPayload(
                    principal.userId(), principal.username(), principal.displayName());
        }
    }
}
