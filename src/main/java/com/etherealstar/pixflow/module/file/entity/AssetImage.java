package com.etherealstar.pixflow.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 单张图片实体，对应表 {@code asset_image}。
 * <p>通过 {@code sku_id} 与 {@code asset_copy} 软关联，无数据库外键（需求 3.8）。
 */
@TableName("asset_image")
public class AssetImage {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属素材包 id（软关联） */
    private Long packageId;

    /** 从文件名提取的 SKU ID（≤255） */
    private String skuId;

    /** 相对 zip 根目录的相对路径 */
    private String originalPath;

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

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
