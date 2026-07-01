package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("rubrics_score")
public class RubricsScoreEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resultId;
    private Long taskId;
    private Long runId;
    private String templateId;
    private String templateVersion;
    private BigDecimal overallScore;
    private BigDecimal imageScore;
    private BigDecimal copyScore;
    private BigDecimal decisionScore;
    private String dimensionScoresJson;
    private String explanationJson;
    private Boolean alertFlag;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public BigDecimal getOverallScore() { return overallScore; }
    public void setOverallScore(BigDecimal overallScore) { this.overallScore = overallScore; }
    public BigDecimal getImageScore() { return imageScore; }
    public void setImageScore(BigDecimal imageScore) { this.imageScore = imageScore; }
    public BigDecimal getCopyScore() { return copyScore; }
    public void setCopyScore(BigDecimal copyScore) { this.copyScore = copyScore; }
    public BigDecimal getDecisionScore() { return decisionScore; }
    public void setDecisionScore(BigDecimal decisionScore) { this.decisionScore = decisionScore; }
    public String getDimensionScoresJson() { return dimensionScoresJson; }
    public void setDimensionScoresJson(String dimensionScoresJson) { this.dimensionScoresJson = dimensionScoresJson; }
    public String getExplanationJson() { return explanationJson; }
    public void setExplanationJson(String explanationJson) { this.explanationJson = explanationJson; }
    public Boolean getAlertFlag() { return alertFlag; }
    public void setAlertFlag(Boolean alertFlag) { this.alertFlag = alertFlag; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
