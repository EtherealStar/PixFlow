package com.pixflow.module.file.internal.deletion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("asset_cleanup_intent")
public class AssetCleanupIntent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String referenceKind;

    private Long packageId;

    private Long imageId;

    private String storageBucket;

    private String storageKey;

    private Boolean prefixCleanup;

    private String status;

    private Integer attemptCount;

    private String lastError;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getReferenceKind() {
        return referenceKind;
    }

    public Long getPackageId() {
        return packageId;
    }

    public Long getImageId() {
        return imageId;
    }

    public String getStorageBucket() {
        return storageBucket;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Boolean getPrefixCleanup() {
        return prefixCleanup;
    }
}
