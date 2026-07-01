package com.pixflow.infra.auth.session;

import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;

public class RedisAccessTokenBlacklist implements AccessTokenBlacklist {
    private final CacheStore cacheStore;
    private final CacheNamespace namespace;

    public RedisAccessTokenBlacklist(CacheStore cacheStore, CacheNamespace namespace) {
        this.cacheStore = cacheStore;
        this.namespace = namespace;
    }

    @Override
    public void revoke(String jwtId, Duration ttl) {
        cacheStore.put(namespace.key("auth", "blacklist", "access", jwtId), Boolean.TRUE, ttl);
    }

    @Override
    public boolean isRevoked(String jwtId) {
        return cacheStore.exists(namespace.key("auth", "blacklist", "access", jwtId));
    }
}
