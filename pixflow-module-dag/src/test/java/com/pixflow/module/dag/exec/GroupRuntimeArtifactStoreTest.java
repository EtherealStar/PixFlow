package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.runtime.RunStateRefStore;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GroupRuntimeArtifactStoreTest {

    @Test
    void missWritesTmpBeforeReferenceAndCleansBothAtUnitEnd() {
        RunStateRefStore refs = mock(RunStateRefStore.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UnitKey unit = UnitKey.group("42", "group-1", "branch-a");
        AtomicInteger computed = new AtomicInteger();
        when(refs.getRef(any(), eq(7L))).thenReturn(Optional.empty());

        try (var session = new GroupRuntimeArtifactStore(refs, storage).open(unit, 7)) {
            byte[] bytes = session.getOrCompute("image-1", () -> {
                computed.incrementAndGet();
                return new byte[]{1, 2, 3};
            });
            assertThat(bytes).containsExactly(1, 2, 3);
        }

        assertThat(computed).hasValue(1);
        verify(storage).put(any(ObjectLocation.class), any(), eq(3L), eq("application/octet-stream"));
        verify(refs).putRef(any(CacheKey.class), any(RuntimeArtifactRef.class), eq(Duration.ofHours(24)));
        verify(refs).deleteRef(any(CacheKey.class));
        verify(storage).delete(any(ObjectLocation.class));
    }

    @Test
    void sameEpochHitReadsTmpWithoutRecomputing() {
        RunStateRefStore refs = mock(RunStateRefStore.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UnitKey unit = UnitKey.group("42", "group-1", "branch-a");
        ObjectLocation location = ObjectLocation.of(com.pixflow.infra.storage.BucketType.TMP, "cached.bin");
        when(refs.getRef(any(), eq(7L))).thenReturn(Optional.of(
                new RuntimeArtifactRef(unit, 7, location, Map.of())));
        when(storage.getBytes(location)).thenReturn(new byte[]{9});

        try (var session = new GroupRuntimeArtifactStore(refs, storage).open(unit, 7)) {
            assertThat(session.getOrCompute("image-1", () -> {
                throw new AssertionError("same epoch ref should avoid recompute");
            })).containsExactly(9);
        }

        verify(storage, never()).put(any(), any(), eq(1L), any());
    }
}
