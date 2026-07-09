package com.pixflow.infra.auth.session;

import java.time.Duration;

public interface AccessTokenBlacklist {
    /**
     * Revokes an access token id for a positive ttl. jwtId must not be blank.
     */
    void revoke(String jwtId, Duration ttl);

    /**
     * Returns whether an access token id has been revoked. jwtId must not be blank.
     */
    boolean isRevoked(String jwtId);
}
