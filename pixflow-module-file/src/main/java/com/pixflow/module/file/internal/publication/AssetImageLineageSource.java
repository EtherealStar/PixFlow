package com.pixflow.module.file.internal.publication;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("asset_image_lineage_source")
public class AssetImageLineageSource {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long assetImageId;

  private Integer ordinal;

  private String sourceImageId;

  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getAssetImageId() {
    return assetImageId;
  }

  public void setAssetImageId(Long assetImageId) {
    this.assetImageId = assetImageId;
  }

  public Integer getOrdinal() {
    return ordinal;
  }

  public void setOrdinal(Integer ordinal) {
    this.ordinal = ordinal;
  }

  public String getSourceImageId() {
    return sourceImageId;
  }

  public void setSourceImageId(String sourceImageId) {
    this.sourceImageId = sourceImageId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
