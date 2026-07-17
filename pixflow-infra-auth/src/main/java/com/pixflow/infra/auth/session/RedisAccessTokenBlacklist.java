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
        requireJwtId(jwtId);
        requirePositiveTtl(ttl);
        cacheStore.put(namespace.key("auth", "blacklist", "access", jwtId), Boolean.TRUE, ttl);
    }

    @Override
    public boolean isRevoked(String jwtId) {
        requireJwtId(jwtId);
        return cacheStore.exists(namespace.key("auth", "blacklist", "access", jwtId));
    }

    private static void requireJwtId(String jwtId) {
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
    }

    private static void requirePositiveTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
