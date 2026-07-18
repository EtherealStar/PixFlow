package com.pixflow.module.task.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("process_result")
public class ProcessResult {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;

  private String unitKey;

  private com.pixflow.harness.state.model.UnitKind unitKind;

  private String imageId;

  private String skuId;

  private String groupKey;

  private String viewId;

  private String branchId;

  private String sourcePath;

  private String outputMinioKey;

  private String candidateBucket;

  private String candidateContentType;

  private String candidateExtension;

  private String producerKind;

  private String producerProvider;

  private String producerModel;

  private String producerTool;

  private String producerNodeId;

  private PublicationStatus publicationStatus;

  private Long generatedImageId;

  private String publishedReferenceKey;

  private Instant publishedAt;

  private Integer publicationAttemptCount;

  private String publicationLastError;

  private String displayName;

  private String generatedCopy;

  private ResultStatus status;

  private Long runEpoch;

  private String failureCode;

  private String failureCategory;

  private String failureRecovery;

  private String failedNodeId;

  private String failedTool;

  private String failureDetailsJson;

  private String errorMsg;

  private Integer attemptCount;

  private Instant startedAt;

  private Instant finishedAt;

  private Long bytesIn;

  private Long bytesOut;

  private Instant deletedAt;

  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long taskId) {
    this.taskId = taskId;
  }

  public String getUnitKey() {
    return unitKey;
  }

  public void setUnitKey(String unitKey) {
    this.unitKey = unitKey;
  }

  public com.pixflow.harness.state.model.UnitKind getUnitKind() {
    return unitKind;
  }

  public void setUnitKind(com.pixflow.harness.state.model.UnitKind unitKind) {
    this.unitKind = unitKind;
  }

  public String getImageId() {
    return imageId;
  }

  public void setImageId(String imageId) {
    this.imageId = imageId;
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

  public String getBranchId() {
    return branchId;
  }

  public void setBranchId(String branchId) {
    this.branchId = branchId;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public void setSourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
  }

  public String getOutputMinioKey() {
    return outputMinioKey;
  }

  public void setOutputMinioKey(String outputMinioKey) {
    this.outputMinioKey = outputMinioKey;
  }

  public String getCandidateBucket() {
    return candidateBucket;
  }

  public void setCandidateBucket(String candidateBucket) {
    this.candidateBucket = candidateBucket;
  }

  public String getCandidateContentType() {
    return candidateContentType;
  }

  public void setCandidateContentType(String candidateContentType) {
    this.candidateContentType = candidateContentType;
  }

  public String getCandidateExtension() {
    return candidateExtension;
  }

  public void setCandidateExtension(String candidateExtension) {
    this.candidateExtension = candidateExtension;
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

  public PublicationStatus getPublicationStatus() {
    return publicationStatus;
  }

  public void setPublicationStatus(PublicationStatus publicationStatus) {
    this.publicationStatus = publicationStatus;
  }

  public Long getGeneratedImageId() {
    return generatedImageId;
  }

  public void setGeneratedImageId(Long generatedImageId) {
    this.generatedImageId = generatedImageId;
  }

  public String getPublishedReferenceKey() {
    return publishedReferenceKey;
  }

  public void setPublishedReferenceKey(String publishedReferenceKey) {
    this.publishedReferenceKey = publishedReferenceKey;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public Integer getPublicationAttemptCount() {
    return publicationAttemptCount;
  }

  public void setPublicationAttemptCount(Integer publicationAttemptCount) {
    this.publicationAttemptCount = publicationAttemptCount;
  }

  public String getPublicationLastError() {
    return publicationLastError;
  }

  public void setPublicationLastError(String publicationLastError) {
    this.publicationLastError = publicationLastError;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getGeneratedCopy() {
    return generatedCopy;
  }

  public void setGeneratedCopy(String generatedCopy) {
    this.generatedCopy = generatedCopy;
  }

  public ResultStatus getStatus() {
    return status;
  }

  public void setStatus(ResultStatus status) {
    this.status = status;
  }

  public Long getRunEpoch() {
    return runEpoch;
  }

  public void setRunEpoch(Long runEpoch) {
    this.runEpoch = runEpoch;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String failureCode) {
    this.failureCode = failureCode;
  }

  public String getFailureCategory() {
    return failureCategory;
  }

  public void setFailureCategory(String failureCategory) {
    this.failureCategory = failureCategory;
  }

  public String getFailureRecovery() {
    return failureRecovery;
  }

  public void setFailureRecovery(String failureRecovery) {
    this.failureRecovery = failureRecovery;
  }

  public String getFailedNodeId() {
    return failedNodeId;
  }

  public void setFailedNodeId(String failedNodeId) {
    this.failedNodeId = failedNodeId;
  }

  public String getFailedTool() {
    return failedTool;
  }

  public void setFailedTool(String failedTool) {
    this.failedTool = failedTool;
  }

  public String getFailureDetailsJson() {
    return failureDetailsJson;
  }

  public void setFailureDetailsJson(String failureDetailsJson) {
    this.failureDetailsJson = failureDetailsJson;
  }

  public String getErrorMsg() {
    return errorMsg;
  }

  public void setErrorMsg(String errorMsg) {
    this.errorMsg = errorMsg;
  }

  public Integer getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(Integer attemptCount) {
    this.attemptCount = attemptCount;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public Long getBytesIn() {
    return bytesIn;
  }

  public void setBytesIn(Long bytesIn) {
    this.bytesIn = bytesIn;
  }

  public Long getBytesOut() {
    return bytesOut;
  }

  public void setBytesOut(Long bytesOut) {
    this.bytesOut = bytesOut;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
