package com.pixflow.infra.storage.toolresult;

import java.util.Objects;

public record StoredToolResultReference(
        String id,
        String bucket,
        String key,
        String preview,
        long originalBytes,
        boolean missing) {

    public StoredToolResultReference {
        id = Objects.requireNonNull(id, "id");
        bucket = Objects.requireNonNull(bucket, "bucket");
        key = Objects.requireNonNull(key, "key");
        preview = preview == null ? "" : preview;
    }

    public StoredToolResultReference asMissing() {
        return new StoredToolResultReference(id, bucket, key, preview, originalBytes, true);
    }
}
