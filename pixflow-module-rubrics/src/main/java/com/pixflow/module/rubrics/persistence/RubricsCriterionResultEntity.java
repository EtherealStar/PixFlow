package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("rubrics_criterion_result")
public class RubricsCriterionResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long evaluationId;

    private String criterionKey;

    private CriterionKind criterionKind;

    private CriterionVerdict verdict;

    private VerdictReason reasonCode;

    private String rationale;

    private String evidenceIdsJson;

    private String diagnosticsJson;

    private BigDecimal agreement;

    private Integer rolloutCount;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public Long getEvaluationId() {
        return evaluationId;
    }

    public void setEvaluationId(Long value) {
        evaluationId = value;
    }

    public String getCriterionKey() {
        return criterionKey;
    }

    public void setCriterionKey(String value) {
        criterionKey = value;
    }

    public CriterionKind getCriterionKind() {
        return criterionKind;
    }

    public void setCriterionKind(CriterionKind value) {
        criterionKind = value;
    }

    public CriterionVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(CriterionVerdict value) {
        verdict = value;
    }

    public VerdictReason getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(VerdictReason value) {
        reasonCode = value;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String value) {
        rationale = value;
    }

    public String getEvidenceIdsJson() {
        return evidenceIdsJson;
    }

    public void setEvidenceIdsJson(String value) {
        evidenceIdsJson = value;
    }

    public String getDiagnosticsJson() {
        return diagnosticsJson;
    }

    public void setDiagnosticsJson(String value) {
        diagnosticsJson = value;
    }

    public BigDecimal getAgreement() {
        return agreement;
    }

    public void setAgreement(BigDecimal value) {
        agreement = value;
    }

    public Integer getRolloutCount() {
        return rolloutCount;
    }

    public void setRolloutCount(Integer value) {
        rolloutCount = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        createdAt = value;
    }
}
