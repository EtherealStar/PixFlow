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
 @TableId(type=IdType.AUTO) private Long id; private Long evaluationId; private String criterionKey;
 private CriterionKind criterionKind; private CriterionVerdict verdict; private VerdictReason reasonCode;
 private String rationale; private String evidenceIdsJson; private String diagnosticsJson; private BigDecimal agreement; private Instant createdAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getEvaluationId(){return evaluationId;} public void setEvaluationId(Long v){evaluationId=v;}
 public String getCriterionKey(){return criterionKey;} public void setCriterionKey(String v){criterionKey=v;} public CriterionKind getCriterionKind(){return criterionKind;} public void setCriterionKind(CriterionKind v){criterionKind=v;}
 public CriterionVerdict getVerdict(){return verdict;} public void setVerdict(CriterionVerdict v){verdict=v;} public VerdictReason getReasonCode(){return reasonCode;} public void setReasonCode(VerdictReason v){reasonCode=v;}
 public String getRationale(){return rationale;} public void setRationale(String v){rationale=v;} public String getEvidenceIdsJson(){return evidenceIdsJson;} public void setEvidenceIdsJson(String v){evidenceIdsJson=v;}
 public String getDiagnosticsJson(){return diagnosticsJson;} public void setDiagnosticsJson(String v){diagnosticsJson=v;} public BigDecimal getAgreement(){return agreement;} public void setAgreement(BigDecimal v){agreement=v;}
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
