package com.pixflow.infra.auth.session;

import java.time.Duration;

public interface AccessTokenBlacklist {
    void revoke(String jwtId, Duration ttl);

    boolean isRevoked(String jwtId);
}
