package com.pixflow.module.dag.exec;

import com.pixflow.harness.state.model.RuntimeArtifactRef;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.harness.state.runtime.RunStateRefStore;
import com.pixflow.harness.state.runtime.RuntimeRefKey;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 当前 Group Work Unit 的临时成员产物；引用和字节均不构成 checkpoint。 */
public final class GroupRuntimeArtifactStore {
    private static final Duration FALLBACK_TTL = Duration.ofHours(24);
    private final RunStateRefStore refs;
    private final ObjectStorage storage;

    public GroupRuntimeArtifactStore(RunStateRefStore refs, ObjectStorage storage) {
        this.refs = refs;
        this.storage = storage;
    }

    public Session open(UnitKey unitKey, long runEpoch) {
        return new Session(unitKey, runEpoch);
    }

    public final class Session implements AutoCloseable {
        private final UnitKey unitKey;
        private final long runEpoch;
        private final List<Entry> touched = new ArrayList<>();

        private Session(UnitKey unitKey, long runEpoch) {
            if (unitKey == null || unitKey.kind() != com.pixflow.harness.state.model.UnitKind.GROUP) {
                throw new IllegalArgumentException("group UnitKey required");
            }
            if (runEpoch <= 0) {
                throw new IllegalArgumentException("runEpoch must be positive");
            }
            this.unitKey = unitKey;
            this.runEpoch = runEpoch;
        }

        public byte[] getOrCompute(String memberId, Supplier<byte[]> producer) {
            Entry entry = entry(memberId);
            touched.add(entry);
            var cached = refs.getRef(entry.cacheKey(), runEpoch);
            if (cached.isPresent()) {
                return storage.getBytes(cached.get().location());
            }

            byte[] bytes = producer.get();
            // 先写临时对象，再发布轻量引用；ref 写失败时本次仍可继续 compose。
            storage.put(entry.location(), new ByteArrayInputStream(bytes), bytes.length,
                    "application/octet-stream");
            refs.putRef(entry.cacheKey(), new RuntimeArtifactRef(
                    unitKey, runEpoch, entry.location(), Map.of("memberId", memberId)), FALLBACK_TTL);
            return bytes;
        }

        @Override
        public void close() {
            for (Entry entry : touched) {
                refs.deleteRef(entry.cacheKey());
                try {
                    storage.delete(entry.location());
                } catch (RuntimeException ignored) {
                    // TMP 生命周期策略负责清理主动删除失败的孤儿对象。
                }
            }
        }

        private Entry entry(String memberId) {
            String hash = UnitKeyCodec.sha256(unitKey);
            ObjectLocation location = StorageKeys.runtimeGroup(
                    unitKey.taskId(), runEpoch, hash, memberId, "prepared.bin");
            String value = "runref:group:" + unitKey.taskId() + ":" + runEpoch + ":" + hash + ":" + memberId;
            return new Entry(new RuntimeRefKey(value, FALLBACK_TTL, "runref:group"), location);
        }
    }

    private record Entry(RuntimeRefKey cacheKey, ObjectLocation location) {
    }
}
