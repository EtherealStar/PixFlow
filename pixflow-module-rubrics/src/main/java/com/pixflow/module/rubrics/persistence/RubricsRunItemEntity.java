package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pixflow.module.rubrics.run.RunItemStatus;
import java.time.Instant;

@TableName("rubrics_run_item")
public class RubricsRunItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long resultId;
    private String subjectType;
    private String subjectId;
    private String subjectSnapshotHash;
    private String qualityGate;
    private java.math.BigDecimal passRate;
    private java.math.BigDecimal coverage;
    private String evidencePackHash;
    private Long taskId;
    private String skuId;
    private RunItemStatus status;
    private Integer attemptCount;
    private String errorMsg;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public String getSubjectType() { return subjectType; } public void setSubjectType(String value) { subjectType = value; }
    public String getSubjectId() { return subjectId; } public void setSubjectId(String value) { subjectId = value; }
    public String getSubjectSnapshotHash() { return subjectSnapshotHash; } public void setSubjectSnapshotHash(String value) { subjectSnapshotHash = value; }
    public String getQualityGate() { return qualityGate; } public void setQualityGate(String value) { qualityGate = value; }
    public java.math.BigDecimal getPassRate() { return passRate; } public void setPassRate(java.math.BigDecimal value) { passRate = value; }
    public java.math.BigDecimal getCoverage() { return coverage; } public void setCoverage(java.math.BigDecimal value) { coverage = value; }
    public String getEvidencePackHash() { return evidencePackHash; } public void setEvidencePackHash(String value) { evidencePackHash = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public RunItemStatus getStatus() { return status; }
    public void setStatus(RunItemStatus status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
