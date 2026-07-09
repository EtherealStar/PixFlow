package com.pixflow.infra.auth.session;

import java.time.Duration;
import java.util.Optional;

public interface AuthSessionStore {
    /**
     * Saves a refresh session. The session must be non-null and ttl must be positive.
     */
    void save(AuthSession session, Duration ttl);

    /**
     * Finds a refresh session without consuming it. refreshJwtId must not be blank.
     */
    Optional<AuthSession> find(String refreshJwtId);

    /**
     * Atomically reads and deletes a refresh session. refreshJwtId must not be blank.
     */
    Optional<AuthSession> consume(String refreshJwtId);

    /**
     * Deletes a refresh session. refreshJwtId must not be blank.
     */
    void delete(String refreshJwtId);
}
