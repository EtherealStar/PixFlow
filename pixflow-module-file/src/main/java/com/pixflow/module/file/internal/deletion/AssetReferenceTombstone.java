package com.pixflow.module.file.internal.deletion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("asset_reference_tombstone")
public class AssetReferenceTombstone {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String referenceKind;

    private Long packageId;

    private String skuId;

    private Long imageId;

    private String displayName;

    public String getReferenceKind() {
        return referenceKind;
    }

    public Long getPackageId() {
        return packageId;
    }

    public String getSkuId() {
        return skuId;
    }

    public Long getImageId() {
        return imageId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setReferenceKind(String referenceKind) {
        this.referenceKind = referenceKind;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public void setImageId(Long imageId) {
        this.imageId = imageId;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
