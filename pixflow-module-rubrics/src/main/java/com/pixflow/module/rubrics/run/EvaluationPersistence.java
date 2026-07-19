package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultEntity;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultMapper;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationEntity;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationMapper;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutEntity;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutMapper;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.summary.EvaluationSummary;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationPersistence {
    private final RubricsEvaluationMapper evaluations;

    private final RubricsCriterionResultMapper criteria;

    private final RubricsJudgeRolloutMapper rollouts;

    private final RunItemClaimRepository claims;

    private final ObjectMapper mapper;

    private final SelfJudgedDetector selfJudgedDetector = new SelfJudgedDetector();

    public EvaluationPersistence(
            RubricsEvaluationMapper evaluations,
            RubricsCriterionResultMapper criteria,
            RubricsJudgeRolloutMapper rollouts,
            RunItemClaimRepository claims,
            ObjectMapper mapper) {
        this.evaluations = evaluations;
        this.criteria = criteria;
        this.rollouts = rollouts;
        this.claims = claims;
        this.mapper = mapper;
    }

    @Transactional
    public long save(
            long runId,
            RunItemClaim claim,
            LoadedTemplate loaded,
            String evaluatorVersion,
            EvaluationSubject subject,
            EvidencePack pack,
            EvaluationSummary summary,
            List<EvaluatedCriterion> values,
            Instant now) {
        try {
            RubricsEvaluationEntity evaluation = evaluationEntity(
                    runId, loaded, evaluatorVersion, subject, pack, summary, values, now);
            evaluations.insert(evaluation);
            for (EvaluatedCriterion value : values) {
                saveCriterion(evaluation.getId(), value, now);
            }
            RunItemStatus terminalStatus = values.stream()
                    .anyMatch(value -> value.result().verdict() == CriterionVerdict.INCONCLUSIVE)
                    ? RunItemStatus.PARTIAL : RunItemStatus.SUCCEEDED;
            if (!claims.finishEvaluation(
                    claim,
                    runId,
                    loaded.canonicalHash(),
                    evaluatorVersion,
                    subject.snapshotHash(),
                    terminalStatus,
                    summary.qualityGate(),
                    summary.passRate(),
                    summary.coverage(),
                    pack.hash(),
                    now)) {
                throw new IllegalStateException("evaluation claim was lost before commit");
            }
            return evaluation.getId();
        } catch (Exception error) {
            throw new IllegalStateException("failed to persist rubric evaluation", error);
        }
    }

    private RubricsEvaluationEntity evaluationEntity(
            long runId,
            LoadedTemplate loaded,
            String evaluatorVersion,
            EvaluationSubject subject,
            EvidencePack pack,
            EvaluationSummary summary,
            List<EvaluatedCriterion> values,
            Instant now) throws com.fasterxml.jackson.core.JsonProcessingException {
        RubricsEvaluationEntity evaluation = new RubricsEvaluationEntity();
        evaluation.setRunId(runId);
        evaluation.setSubjectType(subject.type());
        evaluation.setSubjectId(subject.id());
        evaluation.setSubjectSnapshotHash(subject.snapshotHash());
        evaluation.setTemplateId(loaded.template().id());
        evaluation.setTemplateVersion(loaded.template().version());
        evaluation.setTemplateHash(loaded.canonicalHash());
        evaluation.setEvaluatorVersion(evaluatorVersion);
        evaluation.setEvidencePackHash(pack.hash());
        evaluation.setEvidenceJson(mapper.writeValueAsString(pack.entries()));
        evaluation.setQualityGate(summary.qualityGate());
        evaluation.setPassRate(decimal(summary.passRate()));
        evaluation.setCoverage(decimal(summary.coverage()));
        evaluation.setSummaryJson(mapper.writeValueAsString(summary));
        evaluation.setSelfJudged(selfJudgedDetector.detect(subject, values));
        evaluation.setCreatedAt(now);
        return evaluation;
    }

    private void saveCriterion(long evaluationId, EvaluatedCriterion value, Instant now)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        RubricsCriterionResultEntity criterion = new RubricsCriterionResultEntity();
        criterion.setEvaluationId(evaluationId);
        criterion.setCriterionKey(value.key());
        criterion.setCriterionKind(value.kind());
        criterion.setVerdict(value.result().verdict());
        criterion.setReasonCode(value.result().reason());
        criterion.setRationale(value.result().rationale());
        criterion.setEvidenceIdsJson(mapper.writeValueAsString(value.result().evidenceIds()));
        criterion.setDiagnosticsJson(mapper.writeValueAsString(value.result().diagnostics()));
        criterion.setAgreement(decimal(value.agreement()));
        criterion.setRolloutCount(value.rollouts().size());
        criterion.setCreatedAt(now);
        criteria.insert(criterion);
        for (var rollout : value.rollouts()) {
            RubricsJudgeRolloutEntity entity = new RubricsJudgeRolloutEntity();
            entity.setCriterionResultId(criterion.getId());
            entity.setRolloutIndex(rollout.index());
            entity.setVerdict(rollout.verdict());
            entity.setReasonCode(rollout.reason());
            entity.setRationale(rollout.rationale());
            entity.setEvidenceIdsJson(mapper.writeValueAsString(rollout.evidenceIds()));
            entity.setProvider(rollout.provider());
            entity.setModel(rollout.model());
            entity.setPromptHash(rollout.promptHash());
            entity.setLatencyMs(rollout.latencyMs());
            entity.setUsageJson(mapper.writeValueAsString(Map.of(
                    "promptTokens", rollout.promptTokens(),
                    "completionTokens", rollout.completionTokens(),
                    "totalTokens", rollout.totalTokens())));
            entity.setErrorCode(rollout.verdict() == CriterionVerdict.INCONCLUSIVE
                    ? rollout.reason().name() : null);
            entity.setCreatedAt(now);
            rollouts.insert(entity);
        }
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
