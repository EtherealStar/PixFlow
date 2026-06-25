package com.etherealstar.pixflow.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 文案条目实体，对应表 {@code asset_copy}。
 * <p>通过 {@code sku_id} 与 {@code asset_image} 软关联，无数据库外键（需求 3.8）。
 */
@TableName("asset_copy")
public class AssetCopy {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属素材包 id（软关联） */
    private Long packageId;

    /** 软关联键（取文案文档 id 列） */
    private String skuId;

    /** 商品名 */
    private String productName;

    /** 关键词 */
    private String keywords;

    /** 详细描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
