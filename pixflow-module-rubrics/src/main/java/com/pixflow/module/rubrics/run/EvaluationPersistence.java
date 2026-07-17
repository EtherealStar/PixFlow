package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.persistence.*;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.summary.EvaluationSummary;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationPersistence {
 private final RubricsEvaluationMapper evaluations; private final RubricsCriterionResultMapper criteria; private final RubricsJudgeRolloutMapper rollouts; private final RubricsRunItemMapper items; private final ObjectMapper mapper;
 private final SelfJudgedDetector selfJudgedDetector=new SelfJudgedDetector();
 public EvaluationPersistence(RubricsEvaluationMapper e, RubricsCriterionResultMapper c, RubricsJudgeRolloutMapper r, RubricsRunItemMapper i, ObjectMapper m){evaluations=e;criteria=c;rollouts=r;items=i;mapper=m;}
 @Transactional
 public long save(long runId, long itemId, LoadedTemplate loaded, String evaluatorVersion, EvaluationSubject subject, EvidencePack pack, EvaluationSummary summary, List<EvaluatedCriterion> values){
  try {
   var e=new RubricsEvaluationEntity(); e.setRunId(runId); e.setSubjectType(subject.type()); e.setSubjectId(subject.id()); e.setSubjectSnapshotHash(subject.snapshotHash());
   e.setTemplateId(loaded.template().id()); e.setTemplateVersion(loaded.template().version()); e.setTemplateHash(loaded.canonicalHash()); e.setEvaluatorVersion(evaluatorVersion);
   e.setEvidencePackHash(pack.hash()); e.setEvidenceJson(mapper.writeValueAsString(pack.entries())); e.setQualityGate(summary.qualityGate()); e.setPassRate(decimal(summary.passRate())); e.setCoverage(decimal(summary.coverage())); e.setSummaryJson(mapper.writeValueAsString(summary)); e.setSelfJudged(selfJudgedDetector.detect(subject,values)); e.setCreatedAt(Instant.now()); evaluations.insert(e);
   for(var value:values){ var c=new RubricsCriterionResultEntity(); c.setEvaluationId(e.getId()); c.setCriterionKey(value.key()); c.setCriterionKind(value.kind()); c.setVerdict(value.result().verdict()); c.setReasonCode(value.result().reason()); c.setRationale(value.result().rationale()); c.setEvidenceIdsJson(mapper.writeValueAsString(value.result().evidenceIds())); c.setDiagnosticsJson(mapper.writeValueAsString(value.result().diagnostics())); c.setAgreement(decimal(value.agreement())); c.setCreatedAt(Instant.now()); criteria.insert(c);
    for(var valueRollout:value.rollouts()){var r=new RubricsJudgeRolloutEntity();r.setCriterionResultId(c.getId());r.setRolloutIndex(valueRollout.index());r.setVerdict(valueRollout.verdict());r.setReasonCode(valueRollout.reason());r.setRationale(valueRollout.rationale());r.setEvidenceIdsJson(mapper.writeValueAsString(valueRollout.evidenceIds()));r.setProvider(valueRollout.provider());r.setModel(valueRollout.model());r.setPromptHash(valueRollout.promptHash());r.setLatencyMs(valueRollout.latencyMs());r.setUsageJson(mapper.writeValueAsString(Map.of("promptTokens",valueRollout.promptTokens(),"completionTokens",valueRollout.completionTokens(),"totalTokens",valueRollout.totalTokens())));r.setErrorCode(valueRollout.verdict()==com.pixflow.module.rubrics.model.CriterionVerdict.INCONCLUSIVE?valueRollout.reason().name():null);r.setCreatedAt(Instant.now());rollouts.insert(r);}
   }
   RubricsRunItemEntity item=items.selectById(itemId); item.setSubjectSnapshotHash(subject.snapshotHash()); item.setQualityGate(summary.qualityGate().name()); item.setPassRate(decimal(summary.passRate())); item.setCoverage(decimal(summary.coverage())); item.setEvidencePackHash(pack.hash()); item.setStatus(values.stream().anyMatch(v->v.result().verdict()==com.pixflow.module.rubrics.model.CriterionVerdict.INCONCLUSIVE)?RunItemStatus.PARTIAL:RunItemStatus.SUCCEEDED); item.setFinishedAt(Instant.now()); item.setUpdatedAt(Instant.now()); items.updateById(item);
   return e.getId();
  } catch(Exception error){throw new IllegalStateException("failed to persist rubric evaluation",error);}
 }
 private static BigDecimal decimal(Double value){return value==null?null:BigDecimal.valueOf(value);}
}
