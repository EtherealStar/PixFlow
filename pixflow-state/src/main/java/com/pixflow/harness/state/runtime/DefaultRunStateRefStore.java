package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultRunStateRefStore implements RunStateRefStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRunStateRefStore.class);

    private final CacheStore cacheStore;

    public DefaultRunStateRefStore(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    public void putRef(RuntimeRefKey key, RuntimeArtifactRef ref, Duration ttl) {
        try {
            cacheStore.put(toCacheKey(key), ref, ttl);
        } catch (RuntimeException ex) {
            // 引用写失败只损失下一次避算机会，不能阻断工作单元主流程。
            LOGGER.warn("failed to put state artifact ref, degrade to recompute on next read: {}", key.namespace(), ex);
        }
    }

    @Override
    public Optional<RuntimeArtifactRef> getRef(RuntimeRefKey key, long expectedRunEpoch) {
        if (expectedRunEpoch <= 0) {
            throw new IllegalArgumentException("expectedRunEpoch must be positive");
        }
        try {
            Optional<RuntimeArtifactRef> ref = cacheStore.get(toCacheKey(key), RuntimeArtifactRef.class);
            if (ref.isPresent() && ref.get().runEpoch() != expectedRunEpoch) {
                // 旧 epoch 的中间产物不得进入当前执行，删除失败时仍由 TTL 兜底。
                deleteRef(key);
                return Optional.empty();
            }
            return ref;
        } catch (RuntimeException ex) {
            LOGGER.warn("failed to read state artifact ref, treat as cache miss: {}", key.namespace(), ex);
            return Optional.empty();
        }
    }

    @Override
    public void deleteRef(RuntimeRefKey key) {
        try {
            cacheStore.delete(toCacheKey(key));
        } catch (RuntimeException ex) {
            LOGGER.warn("failed to delete state artifact ref, ttl will clean it up: {}", key.namespace(), ex);
        }
    }

    private CacheKey toCacheKey(RuntimeRefKey key) {
        return new CacheKey(key.value(), key.ttl(), key.namespace());
    }
}
