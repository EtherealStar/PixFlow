package com.pixflow.infra.auth;

import com.pixflow.infra.auth.session.AccessTokenBlacklist;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

class InMemoryAccessTokenBlacklist implements AccessTokenBlacklist {
    private final Set<String> revoked = new HashSet<>();

    @Override
    public void revoke(String jwtId, Duration ttl) {
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        revoked.add(jwtId);
    }

    @Override
    public boolean isRevoked(String jwtId) {
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
        return revoked.contains(jwtId);
    }
}
