package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import java.time.Duration;
import java.util.Optional;

public interface RunStateRefStore {
    void putRef(RuntimeRefKey key, RuntimeArtifactRef ref, Duration ttl);

    Optional<RuntimeArtifactRef> getRef(RuntimeRefKey key, long expectedRunEpoch);

    void deleteRef(RuntimeRefKey key);
}
