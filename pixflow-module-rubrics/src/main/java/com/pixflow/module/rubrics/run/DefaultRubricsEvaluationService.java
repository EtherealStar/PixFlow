package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.api.DatasetSelection;
import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.EvaluationRunItemView;
import com.pixflow.module.rubrics.api.EvaluationRunReport;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.EvaluationRunStatus;
import com.pixflow.module.rubrics.api.EvaluationRunView;
import com.pixflow.module.rubrics.api.ExplicitSubjects;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.api.RunTrigger;
import com.pixflow.module.rubrics.api.TemplateRef;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.evidence.ImageEvidencePackBuilder;
import com.pixflow.module.rubrics.judge.CriterionEvaluation;
import com.pixflow.module.rubrics.judge.JudgeRollout;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver;
import com.pixflow.module.rubrics.summary.CriterionOutcome;
import com.pixflow.module.rubrics.summary.EvaluationSummary;
import com.pixflow.module.rubrics.summary.EvaluationSummaryCalculator;
import com.pixflow.module.rubrics.template.Applicability;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.rubrics.template.VerifierType;
import com.pixflow.module.rubrics.verifier.CriterionResult;
import com.pixflow.module.rubrics.verifier.RuleCriterionVerifier;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Rubrics 对外深模块接口的默认实现。 */
public final class DefaultRubricsEvaluationService implements RubricsEvaluationService {
    private static final int MAX_VIEW_ITEMS = 100;

    private static final String NON_REPLAYABLE_PREFIX = "NON_REPLAYABLE:";

    private final TemplateRegistry templates;

    private final ImageSubjectSnapshotResolver subjects;

    private final ImageEvidencePackBuilder evidence;

    private final RuleCriterionVerifier rules;

    private final RepeatedLlmCriterionVerifier llm;

    private final EvaluationSummaryCalculator summaries;

    private final EvaluationPersistence persistence;

    private final RubricsRunMapper runs;

    private final RubricsRunItemMapper items;

    private final RunItemClaimRepository claims;

    private final EvaluationDatasetRepository datasets;

    private final Clock clock;

    private final Duration claimLease;

    private final String workerId;

    public DefaultRubricsEvaluationService(
            TemplateRegistry templates,
            ImageSubjectSnapshotResolver subjects,
            ImageEvidencePackBuilder evidence,
            RuleCriterionVerifier rules,
            RepeatedLlmCriterionVerifier llm,
            EvaluationSummaryCalculator summaries,
            EvaluationPersistence persistence,
            RubricsRunMapper runs,
            RubricsRunItemMapper items,
            RunItemClaimRepository claims,
            EvaluationDatasetRepository datasets,
            Clock clock,
            Duration claimLease) {
        this.templates = templates;
        this.subjects = subjects;
        this.evidence = evidence;
        this.rules = rules;
        this.llm = llm;
        this.summaries = summaries;
        this.persistence = persistence;
        this.runs = runs;
        this.items = items;
        this.claims = claims;
        this.datasets = datasets;
        this.clock = clock;
        this.claimLease = claimLease;
        workerId = "rubrics-" + UUID.randomUUID();
    }

    @Override
    public EvaluationRunView start(EvaluationRunRequest request) {
        LoadedTemplate loaded = templates.require(
                request.template().templateId(), request.template().semanticVersion());
        ResolvedSelection selection = resolveSelection(request);
        if (loaded.template().subjectType() != selection.subjectType()) {
            throw new IllegalArgumentException("template subject type does not match run subject type");
        }
        validateBaseline(request, selection);
        Instant now = clock.instant();
        RubricsRunEntity run = newRun(request, loaded, selection, now);
        runs.insert(run);
        for (SelectedSubject subject : selection.subjects()) {
            items.insert(newItem(run.getId(), selection.subjectType(), subject, now));
        }
        return resume(new EvaluationRunId(run.getId()));
    }

    @Override
    public EvaluationRunView resume(EvaluationRunId runId) {
        RubricsRunEntity run = requireRun(runId.value());
        LoadedTemplate loaded = templates.require(run.getTemplateId(), run.getTemplateVersion());
        List<RubricsRunItemEntity> recoverable = items.findIncompleteByRunId(runId.value());
        if (recoverable.isEmpty()) {
            // Dataset admission 可能直接产生 PARTIAL 项；即使没有可 claim 项，也必须从 item facts 收敛 run。
            recomputeRun(run);
            return view(requireRun(run.getId()));
        }
        run.setStatus(RunStatus.RUNNING);
        run.setFinishedAt(null);
        run.setUpdatedAt(clock.instant());
        runs.updateById(run);
        for (RubricsRunItemEntity item : recoverable) {
            evaluateClaimedItem(run, item, loaded);
        }
        recomputeRun(run);
        return view(requireRun(run.getId()));
    }

    @Override
    public EvaluationRunView get(EvaluationRunId runId) {
        return view(requireRun(runId.value()));
    }

    private void evaluateClaimedItem(
            RubricsRunEntity run, RubricsRunItemEntity item, LoadedTemplate loaded) {
        Instant claimedAt = clock.instant();
        RunItemClaim claim = claims.claim(item.getId(), workerId, claimedAt, claimLease)
                .orElse(null);
        if (claim == null) {
            return;
        }
        try {
            PreparedEvaluation prepared = evaluate(loaded, item.getSubjectId());
            persistence.save(
                    run.getId(),
                    claim,
                    loaded,
                    prepared.evaluatorVersion(),
                    prepared.subject(),
                    prepared.pack(),
                    prepared.summary(),
                    prepared.criteria(),
                    clock.instant());
            run.setEvaluatorVersion(prepared.evaluatorVersion());
        } catch (IllegalArgumentException error) {
            // Subject/schema 等不可恢复错误成为 item 失败；局部 provider 错误已在 criterion 内隔离。
            claims.finish(
                    claim,
                    item.getSubjectSnapshotHash(),
                    RunItemStatus.FAILED,
                    clock.instant());
        } catch (RuntimeException error) {
            // 数据库、序列化和瞬时依赖错误保留恢复资格，watchdog/resume 可在新 epoch 重试。
            claims.failRetryable(claim, error.getClass().getSimpleName(), clock.instant());
        }
    }

    private PreparedEvaluation evaluate(LoadedTemplate loaded, String subjectId) {
        if (loaded.template().subjectType() != SubjectType.IMAGE_RESULT) {
            throw new IllegalArgumentException(
                    "subject adapter is not available: " + loaded.template().subjectType());
        }
        ImageResultSubject subject = subjects.resolve(subjectId);
        EvidencePack pack = evidence.build(subject);
        List<EvaluatedCriterion> evaluated = new ArrayList<>();
        String evaluatorVersion = "deterministic:" + loaded.canonicalHash();
        for (Criterion criterion : loaded.template().criteria()) {
            CriterionEvaluationResult result = evaluateCriterion(criterion, loaded, subject, pack);
            evaluatorVersion = result.evaluatorVersion() == null
                    ? evaluatorVersion : result.evaluatorVersion();
            evaluated.add(new EvaluatedCriterion(
                    criterion.key(), criterion.kind(), result.result(),
                    result.agreement(), result.rollouts()));
        }
        EvaluationSummary summary = summaries.calculate(evaluated.stream()
                .map(value -> new CriterionOutcome(
                        value.key(), value.kind(), value.result().verdict()))
                .toList());
        return new PreparedEvaluation(subject, pack, summary, evaluated, evaluatorVersion);
    }

    private CriterionEvaluationResult evaluateCriterion(
            Criterion criterion,
            LoadedTemplate loaded,
            ImageResultSubject subject,
            EvidencePack pack) {
        List<EvidenceEntry> allowed = pack.view(criterion.evidenceTypes());
        Set<EvidenceType> presentTypes = allowed.stream()
                .map(EvidenceEntry::type)
                .collect(Collectors.toSet());
        if (criterion.applicability() == Applicability.WHEN_EVIDENCE_PRESENT && allowed.isEmpty()) {
            return criterionResult(new CriterionResult(
                    CriterionVerdict.NOT_APPLICABLE,
                    VerdictReason.NOT_APPLICABLE,
                    "required evidence is not present",
                    List.of(),
                    Map.of()));
        }
        if (!presentTypes.containsAll(criterion.evidenceTypes())) {
            return criterionResult(new CriterionResult(
                    CriterionVerdict.INCONCLUSIVE,
                    VerdictReason.MISSING_EVIDENCE,
                    "one or more required evidence types are unavailable",
                    List.of(),
                    Map.of()));
        }
        if (criterion.verifier().type() == VerifierType.RULE) {
            return criterionResult(rules.verify(criterion, allowed));
        }
        CriterionEvaluation judged = llm.verify(
                criterion, loaded.template().evaluator(), subject, pack);
        return new CriterionEvaluationResult(
                judged.result(), judged.agreement(), judged.rollouts(), judged.evaluatorVersion());
    }

    private static CriterionEvaluationResult criterionResult(CriterionResult result) {
        return new CriterionEvaluationResult(result, null, List.of(), null);
    }

    private void recomputeRun(RubricsRunEntity run) {
        List<RubricsRunItemEntity> all = items.findByRunId(run.getId());
        int succeeded = count(all, RunItemStatus.SUCCEEDED);
        int partial = count(all, RunItemStatus.PARTIAL);
        int failed = count(all, RunItemStatus.FAILED);
        int terminal = succeeded + partial + failed;
        RunStatus status;
        if (terminal < all.size()) {
            status = RunStatus.RUNNING;
        } else if (failed == all.size()) {
            status = RunStatus.FAILED;
        } else if (partial > 0 || failed > 0) {
            status = RunStatus.PARTIAL;
        } else {
            status = RunStatus.SUCCEEDED;
        }
        Instant finishedAt = terminal == all.size() ? clock.instant() : null;
        run.setSucceededCount(succeeded);
        run.setIsolatedCount(partial);
        run.setFailedCount(failed);
        run.setStatus(status);
        run.setFinishedAt(finishedAt);
        run.setUpdatedAt(clock.instant());
        runs.updateById(run);
    }

    private EvaluationRunView view(RubricsRunEntity run) {
        List<RubricsRunItemEntity> all = items.findByRunId(run.getId());
        List<EvaluationRunItemView> bounded = all.stream()
                .limit(MAX_VIEW_ITEMS)
                .map(DefaultRubricsEvaluationService::itemView)
                .toList();
        boolean complete = run.getStatus() == RunStatus.SUCCEEDED
                || run.getStatus() == RunStatus.PARTIAL
                || run.getStatus() == RunStatus.FAILED;
        return new EvaluationRunView(
                new EvaluationRunId(run.getId()),
                new TemplateRef(run.getTemplateId(), run.getTemplateVersion()),
                run.getTemplateHash(),
                SubjectType.valueOf(run.getSubjectType()),
                run.getEvaluatorVersion(),
                run.getDatasetId(),
                run.getDatasetVersion(),
                run.getPurpose(),
                run.getBaselineRunId() == null ? null : new EvaluationRunId(run.getBaselineRunId()),
                EvaluationRunStatus.valueOf(run.getStatus().name()),
                run.getTotalCount(),
                run.getSucceededCount(),
                run.getIsolatedCount(),
                run.getFailedCount(),
                bounded,
                all.size() > MAX_VIEW_ITEMS,
                new EvaluationRunReport(
                        run.getPurpose(),
                        Map.of("nonReplayableCount", countNonReplayable(all)),
                        complete));
    }

    private RubricsRunEntity requireRun(long runId) {
        RubricsRunEntity run = runs.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run not found: " + runId);
        }
        return run;
    }

    private static RubricsRunEntity newRun(
            EvaluationRunRequest request,
            LoadedTemplate loaded,
            ResolvedSelection selection,
            Instant now) {
        RubricsRunEntity run = new RubricsRunEntity();
        run.setTemplateId(request.template().templateId());
        run.setTemplateVersion(request.template().semanticVersion());
        run.setTemplateHash(loaded.canonicalHash());
        run.setSubjectType(selection.subjectType().name());
        run.setDatasetId(selection.datasetId());
        run.setDatasetVersion(selection.datasetVersion());
        run.setPurpose(request.purpose());
        run.setBaselineRunId(request.baselineRunId() == null
                ? null : request.baselineRunId().value());
        run.setTriggerType(triggerType(request.trigger()));
        run.setStatus(RunStatus.PENDING);
        run.setTotalCount(selection.subjects().size());
        run.setSucceededCount(0);
        run.setIsolatedCount(0);
        run.setFailedCount(0);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private static RubricsRunItemEntity newItem(
            long runId, SubjectType subjectType, SelectedSubject subject, Instant now) {
        RubricsRunItemEntity item = new RubricsRunItemEntity();
        item.setRunId(runId);
        item.setSubjectType(subjectType.name());
        item.setSubjectId(subject.id());
        item.setSubjectSnapshotHash(subject.expectedSnapshotHash());
        item.setStatus(subject.replayable() ? RunItemStatus.PENDING : RunItemStatus.PARTIAL);
        item.setAttemptCount(0);
        item.setErrorMsg(subject.replayable()
                ? null : NON_REPLAYABLE_PREFIX + safeReplayError(subject.replayError()));
        item.setFinishedAt(subject.replayable() ? null : now);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return item;
    }

    private static EvaluationRunItemView itemView(RubricsRunItemEntity item) {
        QualityGate gate = item.getQualityGate() == null
                ? null : QualityGate.valueOf(item.getQualityGate());
        boolean replayable = item.getErrorMsg() == null
                || !item.getErrorMsg().startsWith(NON_REPLAYABLE_PREFIX);
        return new EvaluationRunItemView(
                SubjectType.valueOf(item.getSubjectType()),
                item.getSubjectId(),
                item.getSubjectSnapshotHash(),
                publicStatus(item.getStatus()),
                gate,
                number(item.getPassRate()),
                number(item.getCoverage()),
                replayable,
                replayable ? null : item.getErrorMsg().substring(NON_REPLAYABLE_PREFIX.length()));
    }

    private static EvaluationRunStatus publicStatus(RunItemStatus status) {
        return switch (status) {
            case PENDING -> EvaluationRunStatus.PENDING;
            case RUNNING, FAILED_RETRYABLE -> EvaluationRunStatus.RUNNING;
            case SUCCEEDED -> EvaluationRunStatus.SUCCEEDED;
            case PARTIAL, ISOLATED -> EvaluationRunStatus.PARTIAL;
            case FAILED -> EvaluationRunStatus.FAILED;
        };
    }

    private static int count(List<RubricsRunItemEntity> values, RunItemStatus status) {
        return (int) values.stream().filter(value -> value.getStatus() == status).count();
    }

    private static int countNonReplayable(List<RubricsRunItemEntity> values) {
        return (int) values.stream()
                .map(RubricsRunItemEntity::getErrorMsg)
                .filter(java.util.Objects::nonNull)
                .filter(error -> error.startsWith(NON_REPLAYABLE_PREFIX))
                .count();
    }

    private static String safeReplayError(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static RunTriggerType triggerType(RunTrigger trigger) {
        return switch (trigger) {
            case MANUAL -> RunTriggerType.MANUAL;
            case EVENT_DRIVEN -> RunTriggerType.TASK_COMPLETED;
            case SCHEDULED -> RunTriggerType.DAILY_BATCH;
        };
    }

    private ResolvedSelection resolveSelection(EvaluationRunRequest request) {
        if (request.selection() instanceof ExplicitSubjects explicit) {
            return new ResolvedSelection(
                    explicit.subjectType(),
                    explicit.subjectIds().stream()
                            .map(id -> new SelectedSubject(id, null, true, null))
                            .toList(),
                    null,
                    null);
        }
        DatasetSelection selected = (DatasetSelection) request.selection();
        EvaluationDatasetRepository.EvaluationDataset dataset =
                datasets.require(selected.datasetId(), selected.datasetVersion());
        List<SelectedSubject> selectedSubjects = dataset.items().stream()
                .map(item -> new SelectedSubject(
                        item.subjectId(), item.subjectSnapshotHash(),
                        item.replayable(), item.replayError()))
                .toList();
        if (selectedSubjects.isEmpty()) {
            throw new IllegalArgumentException("evaluation dataset has no subjects");
        }
        return new ResolvedSelection(
                dataset.subjectType(),
                selectedSubjects,
                dataset.datasetId(),
                dataset.version());
    }

    private void validateBaseline(
            EvaluationRunRequest request, ResolvedSelection selection) {
        if (request.baselineRunId() == null) {
            return;
        }
        RubricsRunEntity baseline = requireRun(request.baselineRunId().value());
        if (baseline.getStatus() != RunStatus.SUCCEEDED
                || baseline.getPurpose() != com.pixflow.module.rubrics.api.RunPurpose.FORMAL_REGRESSION
                || !java.util.Objects.equals(baseline.getDatasetId(), selection.datasetId())
                || !java.util.Objects.equals(
                        baseline.getDatasetVersion(), selection.datasetVersion())) {
            throw new IllegalArgumentException(
                    "formal regression baseline must be a successful run on the same dataset");
        }
        Map<String, String> baselineSnapshots = items.findByRunId(baseline.getId()).stream()
                .collect(Collectors.toMap(
                        RubricsRunItemEntity::getSubjectId,
                        RubricsRunItemEntity::getSubjectSnapshotHash));
        boolean paired = selection.subjects().stream().allMatch(subject ->
                java.util.Objects.equals(
                        baselineSnapshots.get(subject.id()), subject.expectedSnapshotHash()));
        if (!paired || baselineSnapshots.size() != selection.subjects().size()) {
            throw new IllegalArgumentException(
                    "formal regression baseline subject snapshots do not match");
        }
    }

    private record CriterionEvaluationResult(
            CriterionResult result,
            Double agreement,
            List<JudgeRollout> rollouts,
            String evaluatorVersion) {
    }

    private record PreparedEvaluation(
            ImageResultSubject subject,
            EvidencePack pack,
            EvaluationSummary summary,
            List<EvaluatedCriterion> criteria,
            String evaluatorVersion) {
    }

    private record ResolvedSelection(
            SubjectType subjectType,
            List<SelectedSubject> subjects,
            String datasetId,
            String datasetVersion) {
    }

    private record SelectedSubject(
            String id,
            String expectedSnapshotHash,
            boolean replayable,
            String replayError) {
    }
}
