package com.pixflow.infra.auth.session;

import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class RedisAuthSessionStore implements AuthSessionStore {
    private final CacheStore cacheStore;
    private final CacheNamespace namespace;

    public RedisAuthSessionStore(CacheStore cacheStore, CacheNamespace namespace) {
        this.cacheStore = cacheStore;
        this.namespace = namespace;
    }

    @Override
    public void save(AuthSession session, Duration ttl) {
        Objects.requireNonNull(session, "session must not be null");
        requirePositiveTtl(ttl);
        cacheStore.put(namespace.key("auth", "refresh", session.refreshJwtId()), session, ttl);
    }

    @Override
    public Optional<AuthSession> find(String refreshJwtId) {
        requireRefreshJwtId(refreshJwtId);
        return cacheStore.get(namespace.key("auth", "refresh", refreshJwtId), AuthSession.class);
    }

    @Override
    public Optional<AuthSession> consume(String refreshJwtId) {
        requireRefreshJwtId(refreshJwtId);
        return cacheStore.consume(namespace.key("auth", "refresh", refreshJwtId), AuthSession.class);
    }

    @Override
    public void delete(String refreshJwtId) {
        requireRefreshJwtId(refreshJwtId);
        cacheStore.delete(namespace.key("auth", "refresh", refreshJwtId));
    }

    private static void requireRefreshJwtId(String refreshJwtId) {
        if (refreshJwtId == null || refreshJwtId.isBlank()) {
            throw new IllegalArgumentException("refreshJwtId must not be blank");
        }
    }

    private static void requirePositiveTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
