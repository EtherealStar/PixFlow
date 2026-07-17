package com.pixflow.harness.state.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.testsupport.FakeCacheStore;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunStateRefStoreTest {

    @Test
    void storesAndReadsArtifactReference() {
        FakeCacheStore cache = new FakeCacheStore();
        RunStateRefStore store = new DefaultRunStateRefStore(cache);
        RuntimeRefKey key = key();
        RuntimeArtifactRef ref = new RuntimeArtifactRef(
                UnitKey.branch("task-1", "image-1", "branch-a"),
                3,
                ObjectLocation.of(BucketType.TMP, "task-1/image-1/branch-a/node.png"),
                Map.of("format", "png"));

        store.putRef(key, ref, Duration.ofHours(24));

        assertThat(store.getRef(key, 3)).contains(ref);
        assertThat(cache.lastTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(store.getRef(key, 4)).isEmpty();
        assertThat(cache.exists(cacheKey(key))).isFalse();
    }

    @Test
    void readFailureIsTreatedAsMiss() {
        FakeCacheStore cache = new FakeCacheStore();
        cache.failReads(true);

        assertThat(new DefaultRunStateRefStore(cache).getRef(key(), 1)).isEmpty();
    }

    @Test
    void writeAndDeleteFailuresDoNotPropagate() {
        FakeCacheStore cache = new FakeCacheStore();
        cache.failWrites(true);
        cache.failDeletes(true);
        RunStateRefStore store = new DefaultRunStateRefStore(cache);
        RuntimeArtifactRef ref = new RuntimeArtifactRef(
                UnitKey.branch("task-1", "image-1", "branch-a"),
                3,
                ObjectLocation.of(BucketType.TMP, "task-1/image-1/branch-a/node.png"),
                Map.of());

        store.putRef(key(), ref, Duration.ofHours(24));
        store.deleteRef(key());
    }

    private RuntimeRefKey key() {
        return new RuntimeRefKey("test:ref", Duration.ofMinutes(10), "test-ref");
    }

    private com.pixflow.infra.cache.key.CacheKey cacheKey(RuntimeRefKey key) {
        return new com.pixflow.infra.cache.key.CacheKey(key.value(), key.ttl(), key.namespace());
    }
}
