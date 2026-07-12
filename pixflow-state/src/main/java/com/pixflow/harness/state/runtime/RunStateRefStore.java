package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Optional;

public interface RunStateRefStore {
    void putRef(CacheKey key, RuntimeArtifactRef ref, Duration ttl);

    Optional<RuntimeArtifactRef> getRef(CacheKey key, long expectedRunEpoch);

    void deleteRef(CacheKey key);
}
