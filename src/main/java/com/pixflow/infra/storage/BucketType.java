package com.pixflow.infra.storage;

/**
 * 逻辑桶类型，只表达业务层面的对象分类，不绑定物理桶名。
 */
public enum BucketType {
    PACKAGES,
    RESULTS,
    GENERATED,
    TOOL_RESULTS,
    TMP
}
