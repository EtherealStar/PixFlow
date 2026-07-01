package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pixflow.module.rubrics.run.RunStatus;
import com.pixflow.module.rubrics.run.RunTriggerType;
import java.time.Instant;

@TableName("rubrics_run")
public class RubricsRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateId;
    private String templateVersion;
    private RunTriggerType triggerType;
    private RunStatus status;
    private Integer totalCount;
    private Integer succeededCount;
    private Integer isolatedCount;
    private Integer failedCount;
    private String errorMsg;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public RunTriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(RunTriggerType triggerType) { this.triggerType = triggerType; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getSucceededCount() { return succeededCount; }
    public void setSucceededCount(Integer succeededCount) { this.succeededCount = succeededCount; }
    public Integer getIsolatedCount() { return isolatedCount; }
    public void setIsolatedCount(Integer isolatedCount) { this.isolatedCount = isolatedCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
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
