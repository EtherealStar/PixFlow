package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.time.Instant;

@TableName("rubrics_judge_rollout")
public class RubricsJudgeRolloutEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long criterionResultId;

    private Integer rolloutIndex;

    private CriterionVerdict verdict;

    private VerdictReason reasonCode;

    private String rationale;

    private String evidenceIdsJson;

    private String provider;

    private String model;

    private String promptHash;

    private Long latencyMs;

    private String usageJson;

    private String errorCode;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public Long getCriterionResultId() {
        return criterionResultId;
    }

    public void setCriterionResultId(Long v) {
        criterionResultId = v;
    }

    public Integer getRolloutIndex() {
        return rolloutIndex;
    }

    public void setRolloutIndex(Integer v) {
        rolloutIndex = v;
    }

    public CriterionVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(CriterionVerdict v) {
        verdict = v;
    }

    public VerdictReason getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(VerdictReason v) {
        reasonCode = v;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String v) {
        rationale = v;
    }

    public String getEvidenceIdsJson() {
        return evidenceIdsJson;
    }

    public void setEvidenceIdsJson(String v) {
        evidenceIdsJson = v;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String v) {
        errorCode = v;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String v) {
        provider = v;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String v) {
        model = v;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public void setPromptHash(String v) {
        promptHash = v;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long v) {
        latencyMs = v;
    }

    public String getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(String v) {
        usageJson = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        createdAt = v;
    }
}
