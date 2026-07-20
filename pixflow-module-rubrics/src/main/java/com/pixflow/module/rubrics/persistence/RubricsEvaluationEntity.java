package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("rubrics_evaluation")
public class RubricsEvaluationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private SubjectType subjectType;

    private String subjectId;

    private String subjectSnapshotHash;

    private String templateId;

    private String templateVersion;

    private String templateHash;

    private String evaluatorVersion;

    private String evidencePackHash;

    private String evidenceJson;

    private QualityGate qualityGate;

    private BigDecimal passRate;

    private BigDecimal coverage;

    private String summaryJson;

    private Boolean selfJudged;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long v) {
        runId = v;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(SubjectType v) {
        subjectType = v;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String v) {
        subjectId = v;
    }

    public String getSubjectSnapshotHash() {
        return subjectSnapshotHash;
    }

    public void setSubjectSnapshotHash(String v) {
        subjectSnapshotHash = v;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String v) {
        templateId = v;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String v) {
        templateVersion = v;
    }

    public String getTemplateHash() {
        return templateHash;
    }

    public void setTemplateHash(String v) {
        templateHash = v;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public void setEvaluatorVersion(String v) {
        evaluatorVersion = v;
    }

    public String getEvidencePackHash() {
        return evidencePackHash;
    }

    public void setEvidencePackHash(String v) {
        evidencePackHash = v;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String v) {
        evidenceJson = v;
    }

    public QualityGate getQualityGate() {
        return qualityGate;
    }

    public void setQualityGate(QualityGate v) {
        qualityGate = v;
    }

    public BigDecimal getPassRate() {
        return passRate;
    }

    public void setPassRate(BigDecimal v) {
        passRate = v;
    }

    public BigDecimal getCoverage() {
        return coverage;
    }

    public void setCoverage(BigDecimal v) {
        coverage = v;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String v) {
        summaryJson = v;
    }

    public Boolean getSelfJudged() {
        return selfJudged;
    }

    public void setSelfJudged(Boolean v) {
        selfJudged = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        createdAt = v;
    }
}
