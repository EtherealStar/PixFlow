package com.pixflow.infra.cache.key;

import java.time.Duration;

/**
 * 统一 Redis key 工厂。
 */
public interface CacheNamespace {
    CacheKey key(String... segments);

    CacheNamespace withDefaultTtl(Duration ttl);
}
