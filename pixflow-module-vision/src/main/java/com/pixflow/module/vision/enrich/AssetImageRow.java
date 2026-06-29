package com.pixflow.module.vision.enrich;

public class AssetImageRow {
    private Long id;
    private Long packageId;
    private String skuId;
    private String viewId;
    private String minioKey;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPackageId() {
        return packageId;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getViewId() {
        return viewId;
    }

    public void setViewId(String viewId) {
        this.viewId = viewId;
    }

    public String getMinioKey() {
        return minioKey;
    }

    public void setMinioKey(String minioKey) {
        this.minioKey = minioKey;
    }
}
