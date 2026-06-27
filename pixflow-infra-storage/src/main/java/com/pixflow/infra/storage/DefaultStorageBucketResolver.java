package com.pixflow.infra.storage;

import org.springframework.util.StringUtils;

/**
 * 默认桶名解析器，从配置读取逻辑桶到物理桶的映射。
 */
public class DefaultStorageBucketResolver implements StorageBucketResolver {
    private final StorageProperties properties;

    public DefaultStorageBucketResolver(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String resolve(BucketType bucket) {
        String value = switch (bucket) {
            case PACKAGES -> properties.getBuckets().getPackages();
            case RESULTS -> properties.getBuckets().getResults();
            case GENERATED -> properties.getBuckets().getGenerated();
            case TOOL_RESULTS -> properties.getBuckets().getToolResults();
            case TMP -> properties.getBuckets().getTmp();
        };
        if (!StringUtils.hasText(value)) {
            throw new StorageException("RESOLVE_BUCKET", bucket, null, false, "bucket name is missing", null);
        }
        return value;
    }
}
