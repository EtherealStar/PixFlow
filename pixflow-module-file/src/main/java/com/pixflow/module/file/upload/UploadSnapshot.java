package com.pixflow.module.file.upload;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record UploadSnapshot(UploadSession session, Map<Integer, ChunkMetadata> chunks) {
    public UploadSnapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunks, "chunks");
        // 快照一经构造便固定顺序和内容，避免业务流程中多次读取产生漂移。
        chunks = Collections.unmodifiableMap(new TreeMap<>(chunks));
    }
}
