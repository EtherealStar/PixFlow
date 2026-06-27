package com.pixflow.infra.storage;

/**
 * 逻辑桶到物理桶名的解析器。
 */
public interface StorageBucketResolver {
    String resolve(BucketType bucket);
}
