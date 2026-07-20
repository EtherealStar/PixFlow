package com.pixflow.module.file.image;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("asset_image")
public class AssetImage {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long packageId;

  private String skuId;

  private String groupKey;

  private String viewId;

  private String minioKey;

  private String originalPath;

  private String displayName;

  private String sourceType;

  private String publicationStatus;

  private String candidateBucket;

  private String candidateKey;

  private String stableBucket;

  private String contentType;

  private Long byteSize;

  private String contentHash;

  private Long sourceTaskId;

  private Long sourceResultId;

  private String sourceUnitKey;

  private Long sourceRunEpoch;

  private String sourceImageId;

  private String producerKind;

  private String producerProvider;

  private String producerModel;

  private String producerTool;

  private String producerNodeId;

  private String publicationError;

  private Instant publicationUpdatedAt;

  private Instant readyAt;

  private String cleanupStatus;

  private Integer cleanupAttemptCount;

  private String cleanupLastError;

  private String deletionStatus;

  private Instant createdAt;

  private Instant updatedAt;

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

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
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

  public String getOriginalPath() {
    return originalPath;
  }

  public void setOriginalPath(String originalPath) {
    this.originalPath = originalPath;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getPublicationStatus() {
    return publicationStatus;
  }

  public void setPublicationStatus(String publicationStatus) {
    this.publicationStatus = publicationStatus;
  }

  public String getCandidateBucket() {
    return candidateBucket;
  }

  public void setCandidateBucket(String candidateBucket) {
    this.candidateBucket = candidateBucket;
  }

  public String getCandidateKey() {
    return candidateKey;
  }

  public void setCandidateKey(String candidateKey) {
    this.candidateKey = candidateKey;
  }

  public String getStableBucket() {
    return stableBucket;
  }

  public void setStableBucket(String stableBucket) {
    this.stableBucket = stableBucket;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public Long getByteSize() {
    return byteSize;
  }

  public void setByteSize(Long byteSize) {
    this.byteSize = byteSize;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public Long getSourceTaskId() {
    return sourceTaskId;
  }

  public void setSourceTaskId(Long sourceTaskId) {
    this.sourceTaskId = sourceTaskId;
  }

  public Long getSourceResultId() {
    return sourceResultId;
  }

  public void setSourceResultId(Long sourceResultId) {
    this.sourceResultId = sourceResultId;
  }

  public String getSourceUnitKey() {
    return sourceUnitKey;
  }

  public void setSourceUnitKey(String sourceUnitKey) {
    this.sourceUnitKey = sourceUnitKey;
  }

  public Long getSourceRunEpoch() {
    return sourceRunEpoch;
  }

  public void setSourceRunEpoch(Long sourceRunEpoch) {
    this.sourceRunEpoch = sourceRunEpoch;
  }

  public String getSourceImageId() {
    return sourceImageId;
  }

  public void setSourceImageId(String sourceImageId) {
    this.sourceImageId = sourceImageId;
  }

  public String getProducerKind() {
    return producerKind;
  }

  public void setProducerKind(String producerKind) {
    this.producerKind = producerKind;
  }

  public String getProducerProvider() {
    return producerProvider;
  }

  public void setProducerProvider(String producerProvider) {
    this.producerProvider = producerProvider;
  }

  public String getProducerModel() {
    return producerModel;
  }

  public void setProducerModel(String producerModel) {
    this.producerModel = producerModel;
  }

  public String getProducerTool() {
    return producerTool;
  }

  public void setProducerTool(String producerTool) {
    this.producerTool = producerTool;
  }

  public String getProducerNodeId() {
    return producerNodeId;
  }

  public void setProducerNodeId(String producerNodeId) {
    this.producerNodeId = producerNodeId;
  }

  public String getPublicationError() {
    return publicationError;
  }

  public void setPublicationError(String publicationError) {
    this.publicationError = publicationError;
  }

  public Instant getPublicationUpdatedAt() {
    return publicationUpdatedAt;
  }

  public void setPublicationUpdatedAt(Instant publicationUpdatedAt) {
    this.publicationUpdatedAt = publicationUpdatedAt;
  }

  public Instant getReadyAt() {
    return readyAt;
  }

  public void setReadyAt(Instant readyAt) {
    this.readyAt = readyAt;
  }

  public String getCleanupStatus() {
    return cleanupStatus;
  }

  public void setCleanupStatus(String cleanupStatus) {
    this.cleanupStatus = cleanupStatus;
  }

  public Integer getCleanupAttemptCount() {
    return cleanupAttemptCount;
  }

  public void setCleanupAttemptCount(Integer cleanupAttemptCount) {
    this.cleanupAttemptCount = cleanupAttemptCount;
  }

  public String getCleanupLastError() {
    return cleanupLastError;
  }

  public void setCleanupLastError(String cleanupLastError) {
    this.cleanupLastError = cleanupLastError;
  }

  public String getDeletionStatus() {
    return deletionStatus;
  }

  public void setDeletionStatus(String deletionStatus) {
    this.deletionStatus = deletionStatus;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
