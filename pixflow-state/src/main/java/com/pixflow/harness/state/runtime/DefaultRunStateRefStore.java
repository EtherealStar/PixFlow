package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.ArtifactRef;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultRunStateRefStore implements RunStateRefStore {
    private static final Logger log = LoggerFactory.getLogger(DefaultRunStateRefStore.class);

    private final CacheStore cacheStore;

    public DefaultRunStateRefStore(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    public void putRef(CacheKey key, ArtifactRef ref, Duration ttl) {
        try {
            cacheStore.put(key, ref, ttl);
        } catch (RuntimeException ex) {
            // 引用写失败只损失下一次避算机会，不能阻断工作单元主流程。
            log.warn("failed to put state artifact ref, degrade to recompute on next read: {}", key.namespace(), ex);
        }
    }

    @Override
    public Optional<ArtifactRef> getRef(CacheKey key) {
        try {
            return cacheStore.get(key, ArtifactRef.class);
        } catch (RuntimeException ex) {
            log.warn("failed to read state artifact ref, treat as cache miss: {}", key.namespace(), ex);
            return Optional.empty();
        }
    }

    @Override
    public void deleteRef(CacheKey key) {
        try {
            cacheStore.delete(key);
        } catch (RuntimeException ex) {
            log.warn("failed to delete state artifact ref, ttl will clean it up: {}", key.namespace(), ex);
        }
    }
}
