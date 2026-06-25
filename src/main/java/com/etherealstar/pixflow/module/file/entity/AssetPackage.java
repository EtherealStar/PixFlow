package com.etherealstar.pixflow.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 素材包实体，对应表 {@code asset_package}。
 * <p>与 {@code asset_image}、{@code asset_copy} 通过 package_id 软关联，无数据库外键。
 */
@TableName("asset_package")
public class AssetPackage {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 包名（取上传文件名） */
    private String name;

    /** zip 存储路径 */
    private String zipPath;

    /** 文案文档路径，可空 */
    private String docPath;

    /** 上传 zip 体积（字节），供列表按 size 排序（需求 4.3） */
    private Long size;

    /** 成功识别图片数 */
    private Integer imageCount;

    /** 0 解析中 1 就绪 2 解析失败 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getZipPath() {
        return zipPath;
    }

    public void setZipPath(String zipPath) {
        this.zipPath = zipPath;
    }

    public String getDocPath() {
        return docPath;
    }

    public void setDocPath(String docPath) {
        this.docPath = docPath;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Integer getImageCount() {
        return imageCount;
    }

    public void setImageCount(Integer imageCount) {
        this.imageCount = imageCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
