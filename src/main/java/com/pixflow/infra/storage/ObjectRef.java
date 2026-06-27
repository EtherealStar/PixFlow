package com.pixflow.infra.storage;

/**
 * 上传成功后的稳定引用。
 */
public record ObjectRef(BucketType bucket, String key, long size, String etag) {
}
