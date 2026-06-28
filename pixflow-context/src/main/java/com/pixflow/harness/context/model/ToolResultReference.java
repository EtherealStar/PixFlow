package com.pixflow.harness.context.model;

import java.util.Objects;

public record ToolResultReference(
        String id,
        String bucket,
        String key,
        String preview,
        long originalBytes,
        boolean missing) {

    public ToolResultReference {
        id = Objects.requireNonNull(id, "id");
        bucket = Objects.requireNonNull(bucket, "bucket");
        key = Objects.requireNonNull(key, "key");
        preview = preview == null ? "" : preview;
    }
}
