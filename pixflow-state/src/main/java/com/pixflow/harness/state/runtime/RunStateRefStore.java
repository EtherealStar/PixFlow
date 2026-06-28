package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.ArtifactRef;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Optional;

public interface RunStateRefStore {
    void putRef(CacheKey key, ArtifactRef ref, Duration ttl);

    Optional<ArtifactRef> getRef(CacheKey key);

    void deleteRef(CacheKey key);
}
