package com.pixflow.infra.auth.session;

import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
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
        cacheStore.put(namespace.key("auth", "refresh", session.refreshJwtId()), session, ttl);
    }

    @Override
    public Optional<AuthSession> find(String refreshJwtId) {
        return cacheStore.get(namespace.key("auth", "refresh", refreshJwtId), AuthSession.class);
    }

    @Override
    public void delete(String refreshJwtId) {
        cacheStore.delete(namespace.key("auth", "refresh", refreshJwtId));
    }
}
