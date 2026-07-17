package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.service.AuthTokenResponse;
import com.pixflow.infra.auth.service.LoginRequest;
import com.pixflow.infra.auth.service.UserView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthSensitiveToStringTest {
    @Test
    void loginRequestRedactsPassword() {
        assertThat(new LoginRequest("alice", "secret-password").toString())
                .contains("password=<redacted>")
                .doesNotContain("secret-password");
    }

    @Test
    void tokenResponseRedactsTokens() {
        AuthTokenResponse response = new AuthTokenResponse(
                "access-secret",
                Instant.parse("2026-07-01T00:15:00Z"),
                new UserView(1L, "alice", "Alice"),
                "refresh-secret",
                Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(response.toString())
                .contains("accessToken=<redacted>")
                .contains("refreshToken=<redacted>")
                .doesNotContain("access-secret")
                .doesNotContain("refresh-secret");
    }
}
