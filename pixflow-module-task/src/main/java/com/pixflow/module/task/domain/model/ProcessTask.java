package com.pixflow.module.task.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("process_task")
public class ProcessTask {
  @TableId(type = IdType.AUTO)
  private Long id;

  private TaskType taskType;

  private String conversationId;

  private Long packageId;

  private String idempotencyKey;

  private Integer priority;

  private TaskStatus status;

  private String cancelReason;

  private Integer totalCount;

  private Integer doneCount;

  private String dagJson;

  private String unitSelectionJson;

  private String payloadHash;

  private String schemaVersion;

  private Long retryOfTaskId;

  private Long runEpoch;

  private String workerId;

  private Instant enqueuedAt;

  private Instant startedAt;

  private Instant finishedAt;

  private Instant heartbeatAt;

  private String lastError;

  private String errorMsg;

  private Instant createdAt;

  private Instant updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public TaskType getTaskType() {
    return taskType;
  }

  public void setTaskType(TaskType taskType) {
    this.taskType = taskType;
  }

  public String getConversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  public Long getPackageId() {
    return packageId;
  }

  public void setPackageId(Long packageId) {
    this.packageId = packageId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus status) {
    this.status = status;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public void setCancelReason(String cancelReason) {
    this.cancelReason = cancelReason;
  }

  public Integer getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(Integer totalCount) {
    this.totalCount = totalCount;
  }

  public Integer getDoneCount() {
    return doneCount;
  }

  public void setDoneCount(Integer doneCount) {
    this.doneCount = doneCount;
  }

  public String getDagJson() {
    return dagJson;
  }

  public void setDagJson(String dagJson) {
    this.dagJson = dagJson;
  }

  public String getUnitSelectionJson() {
    return unitSelectionJson;
  }

  public void setUnitSelectionJson(String unitSelectionJson) {
    this.unitSelectionJson = unitSelectionJson;
  }

  public String getPayloadHash() {
    return payloadHash;
  }

  public void setPayloadHash(String payloadHash) {
    this.payloadHash = payloadHash;
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public Long getRetryOfTaskId() {
    return retryOfTaskId;
  }

  public void setRetryOfTaskId(Long retryOfTaskId) {
    this.retryOfTaskId = retryOfTaskId;
  }

  public Long getRunEpoch() {
    return runEpoch;
  }

  public void setRunEpoch(Long runEpoch) {
    this.runEpoch = runEpoch;
  }

  public String getWorkerId() {
    return workerId;
  }

  public void setWorkerId(String workerId) {
    this.workerId = workerId;
  }

  public Instant getEnqueuedAt() {
    return enqueuedAt;
  }

  public void setEnqueuedAt(Instant enqueuedAt) {
    this.enqueuedAt = enqueuedAt;
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

  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  public void setHeartbeatAt(Instant heartbeatAt) {
    this.heartbeatAt = heartbeatAt;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public String getErrorMsg() {
    return errorMsg;
  }

  public void setErrorMsg(String errorMsg) {
    this.errorMsg = errorMsg;
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
