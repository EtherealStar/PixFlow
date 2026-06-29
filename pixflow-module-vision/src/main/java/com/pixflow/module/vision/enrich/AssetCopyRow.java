package com.pixflow.module.vision.enrich;

public class AssetCopyRow {
    private Long packageId;
    private String skuId;
    private String productName;
    private String keywords;
    private String description;

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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean hasAnyField() {
        return hasText(productName) || hasText(keywords) || hasText(description);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
