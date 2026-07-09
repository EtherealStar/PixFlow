package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.auth.session.AuthSession;
import com.pixflow.infra.auth.token.AccessTokenClaims;
import com.pixflow.infra.auth.token.IssuedAccessToken;
import com.pixflow.infra.auth.token.RefreshToken;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthRecordContractsTest {
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-01T00:01:00Z");

    @Test
    void accessClaimsRejectInvalidState() {
        assertThatThrownBy(() -> new AccessTokenClaims(null, "alice", "jwt", NOW, LATER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccessTokenClaims(1L, " ", "jwt", NOW, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessTokenClaims(1L, "alice", "jwt", LATER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuedAccessTokenRejectsBlankValues() {
        assertThatThrownBy(() -> new IssuedAccessToken("", "jwt", LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuedAccessToken("token", " ", LATER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshTokenRejectsBlankValues() {
        assertThatThrownBy(() -> new RefreshToken("", "jwt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshToken("token", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authSessionRejectsInvalidState() {
        assertThatThrownBy(() -> new AuthSession("", 1L, "alice", "hash", NOW, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthSession("refresh", null, "alice", "hash", NOW, LATER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthSession("refresh", 1L, "alice", "hash", LATER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
