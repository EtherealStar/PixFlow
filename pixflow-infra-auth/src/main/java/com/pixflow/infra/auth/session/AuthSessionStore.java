package com.pixflow.infra.auth.session;

import java.time.Duration;
import java.util.Optional;

public interface AuthSessionStore {
    void save(AuthSession session, Duration ttl);

    Optional<AuthSession> find(String refreshJwtId);

    void delete(String refreshJwtId);
}
