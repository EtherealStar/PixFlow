package com.pixflow.infra.storage;

import java.time.Instant;

/**
 * 对象元数据快照。
 */
public record StoredObjectMetadata(long size, String contentType, String etag, Instant lastModified) {
}
