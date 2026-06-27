package com.pixflow.infra.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * 对外只暴露纯 I/O 原语，不泄露 MinIO 客户端。
 */
public interface ObjectStorage {
    ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType);

    InputStream getStream(ObjectLocation loc);

    byte[] getBytes(ObjectLocation loc);

    boolean exists(ObjectLocation loc);

    StoredObjectMetadata stat(ObjectLocation loc);

    void delete(ObjectLocation loc);

    void deleteByPrefix(BucketType bucket, String prefix);

    URL presignGet(ObjectLocation loc, Duration ttl);

    URL presignPut(ObjectLocation loc, Duration ttl);
}
