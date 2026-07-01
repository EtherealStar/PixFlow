package com.pixflow.app.auth;

import com.pixflow.infra.auth.service.AuthTokenResponse;
import com.pixflow.infra.auth.service.UserView;
import java.time.Instant;

public record AuthTokenPayload(String accessToken, Instant accessTokenExpiresAt, UserView user) {
    public static AuthTokenPayload from(AuthTokenResponse response) {
        return new AuthTokenPayload(response.accessToken(), response.accessTokenExpiresAt(), response.user());
    }
}
