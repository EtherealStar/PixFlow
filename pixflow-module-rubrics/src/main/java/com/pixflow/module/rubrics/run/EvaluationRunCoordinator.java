package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.api.*;
import com.pixflow.module.rubrics.evidence.*;
import com.pixflow.module.rubrics.judge.*;
import com.pixflow.module.rubrics.model.*;
import com.pixflow.module.rubrics.persistence.*;
import com.pixflow.module.rubrics.subject.*;
import com.pixflow.module.rubrics.summary.*;
import com.pixflow.module.rubrics.template.*;
import com.pixflow.module.rubrics.verifier.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EvaluationRunCoordinator implements RubricsEvaluationService {
 private final TemplateRegistry templates; private final ImageSubjectSnapshotResolver subjects; private final ImageEvidencePackBuilder evidence;
 private final RuleCriterionVerifier rules; private final RepeatedLlmCriterionVerifier llm; private final EvaluationSummaryCalculator summaries; private final EvaluationPersistence persistence;
 private final RubricsRunMapper runs; private final RubricsRunItemMapper items; private final RubricsEvaluationMapper evaluations; private final RubricsCriterionResultMapper criteria; private final RubricsJudgeRolloutMapper rollouts; private final ObjectMapper mapper;
 public EvaluationRunCoordinator(TemplateRegistry t,ImageSubjectSnapshotResolver s,ImageEvidencePackBuilder e,RuleCriterionVerifier r,RepeatedLlmCriterionVerifier l,EvaluationSummaryCalculator sc,EvaluationPersistence p,RubricsRunMapper rm,RubricsRunItemMapper im,RubricsEvaluationMapper em,RubricsCriterionResultMapper cm,RubricsJudgeRolloutMapper jm,ObjectMapper m){templates=t;subjects=s;evidence=e;rules=r;llm=l;summaries=sc;persistence=p;runs=rm;items=im;evaluations=em;criteria=cm;rollouts=jm;mapper=m;}

 public RubricsRunView start(RunEvaluationCommand command){
  return start(command,RunTriggerType.MANUAL);
 }
 public RubricsRunView start(RunEvaluationCommand command,RunTriggerType triggerType){
  LoadedTemplate loaded=templates.require(command.templateId(),command.templateVersion());
  if(loaded.template().subjectType()!=command.subjectType())throw new IllegalArgumentException("template subject type does not match run subject type");
  if(command.datasetId()!=null)throw new IllegalArgumentException("dataset runs require a registered dataset");
  if(command.subjectIds().isEmpty())throw new IllegalArgumentException("subjectIds must not be empty");
  if(command.subjectIds().stream().distinct().count()!=command.subjectIds().size())throw new IllegalArgumentException("subjectIds must be unique");
  var run=new RubricsRunEntity();run.setTemplateId(command.templateId());run.setTemplateVersion(command.templateVersion());run.setTemplateHash(loaded.canonicalHash());run.setSubjectType(command.subjectType().name());run.setTriggerType(java.util.Objects.requireNonNull(triggerType,"triggerType"));run.setStatus(RunStatus.RUNNING);run.setTotalCount(command.subjectIds().size());run.setSucceededCount(0);run.setIsolatedCount(0);run.setFailedCount(0);run.setStartedAt(Instant.now());run.setCreatedAt(Instant.now());run.setUpdatedAt(Instant.now());runs.insert(run);
  int succeeded=0,partial=0,failed=0;String evaluatorVersion="deterministic";
  for(String id:command.subjectIds()){
   RubricsRunItemEntity item=new RubricsRunItemEntity();item.setRunId(run.getId());item.setSubjectType(command.subjectType().name());item.setSubjectId(id);item.setStatus(RunItemStatus.RUNNING);item.setAttemptCount(1);item.setStartedAt(Instant.now());item.setCreatedAt(Instant.now());item.setUpdatedAt(Instant.now());items.insert(item);
   try{EvaluationResult result=evaluate(run.getId(),item.getId(),loaded,id);evaluatorVersion=result.evaluatorVersion();if(result.partial())partial++;else succeeded++;}catch(Exception error){failed++;item.setStatus(RunItemStatus.FAILED);item.setErrorMsg(error.getClass().getSimpleName());item.setFinishedAt(Instant.now());item.setUpdatedAt(Instant.now());items.updateById(item);}
  }
  run.setEvaluatorVersion(evaluatorVersion);run.setSucceededCount(succeeded);run.setIsolatedCount(partial);run.setFailedCount(failed);run.setStatus(failed==command.subjectIds().size()?RunStatus.FAILED:(failed>0||partial>0?RunStatus.PARTIAL:RunStatus.SUCCEEDED));run.setFinishedAt(Instant.now());run.setUpdatedAt(Instant.now());runs.updateById(run);return view(run);
 }
 public RubricsRunView resume(long runId){
  RubricsRunEntity run=runs.selectById(runId);if(run==null)throw new IllegalArgumentException("run not found: "+runId);
  LoadedTemplate loaded=templates.require(run.getTemplateId(),run.getTemplateVersion());List<RubricsRunItemEntity> retryable=items.findIncompleteByRunId(runId);
  if(retryable.isEmpty())return view(run);
  run.setStatus(RunStatus.RUNNING);run.setFinishedAt(null);run.setUpdatedAt(Instant.now());runs.updateById(run);
  String evaluatorVersion=run.getEvaluatorVersion();
  for(RubricsRunItemEntity item:retryable){int attempt=item.getAttemptCount()+1;if(items.markRunning(item.getId(),item.getAttemptCount(),RunItemStatus.RUNNING,Instant.now())==0)continue;try{EvaluationResult result=evaluate(runId,item.getId(),loaded,item.getSubjectId());evaluatorVersion=result.evaluatorVersion();}catch(Exception error){items.markFinished(item.getId(),attempt,RunItemStatus.FAILED,error.getClass().getSimpleName(),Instant.now());}}
  List<RubricsRunItemEntity> all=items.findByRunId(runId);int succeeded=(int)all.stream().filter(i->i.getStatus()==RunItemStatus.SUCCEEDED).count();int partial=(int)all.stream().filter(i->i.getStatus()==RunItemStatus.PARTIAL).count();int failed=all.size()-succeeded-partial;
  run.setEvaluatorVersion(evaluatorVersion);run.setSucceededCount(succeeded);run.setIsolatedCount(partial);run.setFailedCount(failed);run.setStatus(failed==all.size()?RunStatus.FAILED:(failed>0||partial>0?RunStatus.PARTIAL:RunStatus.SUCCEEDED));run.setFinishedAt(Instant.now());run.setUpdatedAt(Instant.now());runs.updateById(run);return view(run);
 }
 private EvaluationResult evaluate(long runId,long itemId,LoadedTemplate loaded,String id){
  if(loaded.template().subjectType()!=SubjectType.IMAGE_RESULT)throw new IllegalArgumentException("subject adapter is not available: "+loaded.template().subjectType());
  ImageResultSubject subject=subjects.resolve(id);EvidencePack pack=evidence.build(subject);List<EvaluatedCriterion> values=new ArrayList<>();String evaluatorVersion="deterministic:"+loaded.canonicalHash();
  for(Criterion criterion:loaded.template().criteria()){
   List<EvidenceEntry> allowed=pack.view(criterion.evidenceTypes()); CriterionResult result;Double agreement=null;List<JudgeRollout> judgeRollouts=List.of();
   java.util.Set<EvidenceType> presentTypes=allowed.stream().map(EvidenceEntry::type).collect(java.util.stream.Collectors.toSet());
   boolean complete=presentTypes.containsAll(criterion.evidenceTypes());
   if(criterion.applicability()==Applicability.WHEN_EVIDENCE_PRESENT&&allowed.isEmpty())result=new CriterionResult(CriterionVerdict.NOT_APPLICABLE,VerdictReason.NOT_APPLICABLE,"required evidence is not present",List.of(),Map.of());
   else if(!complete)result=new CriterionResult(CriterionVerdict.INCONCLUSIVE,VerdictReason.MISSING_EVIDENCE,"one or more required evidence types are unavailable",List.of(),Map.of());
   else if(criterion.verifier().type()==VerifierType.RULE)result=rules.verify(criterion,allowed);
   else {CriterionEvaluation judged=llm.verify(criterion,loaded.template().evaluator(),subject,pack);result=judged.result();agreement=judged.agreement();judgeRollouts=judged.rollouts();evaluatorVersion=judged.evaluatorVersion();}
   values.add(new EvaluatedCriterion(criterion.key(),criterion.kind(),result,agreement,judgeRollouts));
  }
  EvaluationSummary summary=summaries.calculate(values.stream().map(v->new CriterionOutcome(v.key(),v.kind(),v.result().verdict())).toList());
  persistence.save(runId,itemId,loaded,evaluatorVersion,subject,pack,summary,values);return new EvaluationResult(evaluatorVersion,values.stream().anyMatch(v->v.result().verdict()==CriterionVerdict.INCONCLUSIVE));
 }
 public RubricsRunView getRun(long id){RubricsRunEntity run=runs.selectById(id);if(run==null)throw new IllegalArgumentException("run not found: "+id);return view(run);}
 public EvaluationView getEvaluation(long id){RubricsEvaluationEntity e=evaluations.selectById(id);if(e==null)throw new IllegalArgumentException("evaluation not found: "+id);try{List<EvidenceEntry> ev=mapper.readValue(e.getEvidenceJson(),new TypeReference<>(){});List<CriterionResultView> cv=new ArrayList<>();for(var c:criteria.findByEvaluationId(id)){List<String> ids=mapper.readValue(c.getEvidenceIdsJson(),new TypeReference<>(){});List<JudgeRollout> rs=rollouts.findByCriterionResultId(c.getId()).stream().map(r->{Map<String,Long> usage=readUsage(r.getUsageJson());return new JudgeRollout(r.getRolloutIndex(),r.getVerdict(),r.getReasonCode(),r.getRationale(),readIds(r.getEvidenceIdsJson()),r.getProvider(),r.getModel(),r.getPromptHash(),r.getLatencyMs(),usage.getOrDefault("promptTokens",0L),usage.getOrDefault("completionTokens",0L),usage.getOrDefault("totalTokens",0L));}).toList();cv.add(new CriterionResultView(c.getCriterionKey(),c.getCriterionKind(),c.getVerdict(),c.getReasonCode(),c.getRationale(),ids,number(c.getAgreement()),rs));}return new EvaluationView(e.getId(),e.getRunId(),e.getSubjectType(),e.getSubjectId(),e.getSubjectSnapshotHash(),e.getTemplateId(),e.getTemplateVersion(),e.getTemplateHash(),e.getEvaluatorVersion(),e.getEvidencePackHash(),ev,e.getQualityGate(),number(e.getPassRate()),number(e.getCoverage()),cv);}catch(Exception error){throw new IllegalStateException(error);}}
 public List<EvaluationSummaryView> history(SubjectType type,String subjectId){return evaluations.history(type,subjectId).stream().map(e->new EvaluationSummaryView(e.getId(),e.getRunId(),e.getSubjectType(),e.getSubjectId(),e.getQualityGate(),number(e.getPassRate()),number(e.getCoverage()),e.getTemplateVersion(),e.getEvaluatorVersion())).toList();}
 private List<String> readIds(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Long> readUsage(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 private static Double number(BigDecimal v){return v==null?null:v.doubleValue();}
 private static RubricsRunView view(RubricsRunEntity r){return new RubricsRunView(r.getId(),r.getTemplateId(),r.getTemplateVersion(),r.getTemplateHash(),SubjectType.valueOf(r.getSubjectType()),r.getEvaluatorVersion(),r.getStatus(),r.getTotalCount(),r.getSucceededCount(),r.getIsolatedCount(),r.getFailedCount());}
 private record EvaluationResult(String evaluatorVersion,boolean partial){}
}
