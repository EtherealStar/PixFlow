package com.pixflow.infra.auth;

import com.pixflow.infra.auth.session.AccessTokenBlacklist;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

class InMemoryAccessTokenBlacklist implements AccessTokenBlacklist {
    private final Set<String> revoked = new HashSet<>();

    @Override
    public void revoke(String jwtId, Duration ttl) {
        revoked.add(jwtId);
    }

    @Override
    public boolean isRevoked(String jwtId) {
        return revoked.contains(jwtId);
    }
}
