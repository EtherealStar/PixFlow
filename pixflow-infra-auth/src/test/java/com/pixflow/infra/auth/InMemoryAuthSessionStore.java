package com.pixflow.infra.auth;

import com.pixflow.infra.auth.session.AuthSession;
import com.pixflow.infra.auth.session.AuthSessionStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryAuthSessionStore implements AuthSessionStore {
    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(AuthSession session, Duration ttl) {
        sessions.put(session.refreshJwtId(), session);
    }

    @Override
    public Optional<AuthSession> find(String refreshJwtId) {
        return Optional.ofNullable(sessions.get(refreshJwtId));
    }

    @Override
    public Optional<AuthSession> consume(String refreshJwtId) {
        return Optional.ofNullable(sessions.remove(refreshJwtId));
    }

    @Override
    public void delete(String refreshJwtId) {
        sessions.remove(refreshJwtId);
    }

    int size() {
        return sessions.size();
    }
}
