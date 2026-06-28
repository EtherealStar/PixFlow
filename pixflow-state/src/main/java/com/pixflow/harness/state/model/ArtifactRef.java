package com.pixflow.harness.state.model;

import com.pixflow.infra.storage.ObjectLocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ArtifactRef(UnitKey unit, boolean completed, ObjectLocation location, Map<String, Object> meta) {

    public ArtifactRef {
        unit = Objects.requireNonNull(unit, "unit");
        location = Objects.requireNonNull(location, "location");
        // Redis 只保存轻量引用元信息，防御性拷贝避免调用方后续修改缓存载体。
        meta = meta == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(meta));
    }
}
