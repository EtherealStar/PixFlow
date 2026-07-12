package com.pixflow.harness.state.model;

import com.pixflow.infra.storage.ObjectLocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RuntimeArtifactRef(UnitKey unit, long runEpoch, ObjectLocation location, Map<String, Object> meta) {
    public RuntimeArtifactRef {
        unit = Objects.requireNonNull(unit, "unit");
        if (runEpoch <= 0) {
            throw new IllegalArgumentException("runEpoch must be positive");
        }
        location = Objects.requireNonNull(location, "location");
        // Redis 只保存轻量引用；epoch 不一致时它不能参与当前执行，更不能充当 checkpoint。
        meta = meta == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(meta));
    }
}
