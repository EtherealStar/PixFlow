package com.pixflow.harness.state.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.state.model.ArtifactRef;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.testsupport.FakeCacheStore;
import com.pixflow.infra.cache.key.CacheKey;
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
        CacheKey key = key();
        ArtifactRef ref = new ArtifactRef(
                UnitKey.branch("task-1", "image-1", "branch-a"),
                true,
                ObjectLocation.of(BucketType.TMP, "task-1/image-1/branch-a/node.png"),
                Map.of("format", "png"));

        store.putRef(key, ref, Duration.ofMinutes(10));

        assertThat(store.getRef(key)).contains(ref);
    }

    @Test
    void readFailureIsTreatedAsMiss() {
        FakeCacheStore cache = new FakeCacheStore();
        cache.failReads(true);

        assertThat(new DefaultRunStateRefStore(cache).getRef(key())).isEmpty();
    }

    @Test
    void writeAndDeleteFailuresDoNotPropagate() {
        FakeCacheStore cache = new FakeCacheStore();
        cache.failWrites(true);
        cache.failDeletes(true);
        RunStateRefStore store = new DefaultRunStateRefStore(cache);
        ArtifactRef ref = new ArtifactRef(
                UnitKey.branch("task-1", "image-1", "branch-a"),
                false,
                ObjectLocation.of(BucketType.TMP, "task-1/image-1/branch-a/node.png"),
                Map.of());

        store.putRef(key(), ref, Duration.ofMinutes(10));
        store.deleteRef(key());
    }

    private CacheKey key() {
        return new CacheKey("test:ref", Duration.ofMinutes(10), "test-ref");
    }
}
