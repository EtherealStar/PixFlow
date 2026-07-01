package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("rubrics_alert")
public class RubricsAlertEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long baselineRunId;
    private String templateId;
    private String severity;
    private BigDecimal overallDelta;
    private String degradedDimensionsJson;
    private Boolean acknowledged;
    private Instant createdAt;
    private Instant acknowledgedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getBaselineRunId() { return baselineRunId; }
    public void setBaselineRunId(Long baselineRunId) { this.baselineRunId = baselineRunId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public BigDecimal getOverallDelta() { return overallDelta; }
    public void setOverallDelta(BigDecimal overallDelta) { this.overallDelta = overallDelta; }
    public String getDegradedDimensionsJson() { return degradedDimensionsJson; }
    public void setDegradedDimensionsJson(String degradedDimensionsJson) { this.degradedDimensionsJson = degradedDimensionsJson; }
    public Boolean getAcknowledged() { return acknowledged; }
    public void setAcknowledged(Boolean acknowledged) { this.acknowledged = acknowledged; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
