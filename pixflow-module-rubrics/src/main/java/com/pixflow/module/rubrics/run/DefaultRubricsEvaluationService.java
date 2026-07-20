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
import com.pixflow.module.rubrics.evidence.EvidencePackFactory;
import com.pixflow.module.rubrics.judge.CriterionEvaluation;
import com.pixflow.module.rubrics.judge.JudgeRollout;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.subject.EvaluationSubjectCatalog;
import com.pixflow.module.rubrics.summary.CriterionOutcome;
import com.pixflow.module.rubrics.summary.EvaluationSummary;
import com.pixflow.module.rubrics.summary.EvaluationSummaryCalculator;
import com.pixflow.module.rubrics.template.Applicability;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
import com.pixflow.module.rubrics.template.VerifierType;
import com.pixflow.module.rubrics.verifier.CriterionResult;
import com.pixflow.module.rubrics.verifier.RuleCriterionVerifier;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/** Rubrics 对外深模块接口的默认实现。 */
public final class DefaultRubricsEvaluationService implements RubricsEvaluationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRubricsEvaluationService.class);

    private static final int MAX_VIEW_ITEMS = 100;

    private static final String NON_REPLAYABLE_PREFIX = "NON_REPLAYABLE:";

    private final TemplateRegistry templates;

    private final EvaluationSubjectCatalog subjects;

    private final EvidencePackFactory evidence;

    private final RuleCriterionVerifier rules;

    private final RepeatedLlmCriterionVerifier llm;

    private final EvaluationSummaryCalculator summaries;

    private final EvaluationPersistence persistence;

    private final RubricsRunMapper runs;

    private final RubricsRunItemMapper items;

    private final RunItemClaimRepository claims;

    private final EvaluationDatasetRepository datasets;

    private final ValidationReportRepository validationReports;

    private final CalibrationReportRepository calibrationReports;

    private final RegressionReportRepository regressionReports;

    private final RegressionAlertRepository regressionAlerts;

    private final Clock clock;

    private final Duration claimLease;

    private final String workerId;

    private final TransactionOperations transactions;

    private final RubricsMetrics metrics;

    public DefaultRubricsEvaluationService(
            TemplateRegistry templates,
            EvaluationSubjectCatalog subjects,
            EvidencePackFactory evidence,
            RuleCriterionVerifier rules,
            RepeatedLlmCriterionVerifier llm,
            EvaluationSummaryCalculator summaries,
            EvaluationPersistence persistence,
            RubricsRunMapper runs,
            RubricsRunItemMapper items,
            RunItemClaimRepository claims,
            EvaluationDatasetRepository datasets,
            ValidationReportRepository validationReports,
            CalibrationReportRepository calibrationReports,
            RegressionReportRepository regressionReports,
            RegressionAlertRepository regressionAlerts,
            Clock clock,
            Duration claimLease,
            TransactionOperations transactions,
            RubricsMetrics metrics) {
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
        this.validationReports = validationReports;
        this.calibrationReports = calibrationReports;
        this.regressionReports = regressionReports;
        this.regressionAlerts = regressionAlerts;
        this.clock = clock;
        this.claimLease = claimLease;
        this.transactions = transactions;
        this.metrics = metrics;
        workerId = "rubrics-" + UUID.randomUUID();
    }

    @Override
    public EvaluationRunView start(EvaluationRunRequest request) {
        return resume(admit(request, null));
    }

    EvaluationRunId admit(EvaluationRunRequest request, String admissionKey) {
        if (admissionKey != null && admissionKey.isBlank()) {
            throw new IllegalArgumentException("admission key must not be blank");
        }
        EvaluationRunId admitted = transactions.execute(status ->
                admitInTransaction(request, admissionKey));
        if (admitted == null) {
            throw new IllegalStateException("run admission returned no identity");
        }
        return admitted;
    }

    private EvaluationRunId admitInTransaction(
            EvaluationRunRequest request, String admissionKey) {
        RubricsRunEntity existing = admissionKey == null
                ? null : runs.findByAdmissionKey(admissionKey);
        if (existing != null) {
            return new EvaluationRunId(existing.getId());
        }
        LoadedTemplate loaded = templates.require(
                request.template().templateId(), request.template().semanticVersion());
        ResolvedSelection selection = resolveSelection(request);
        if (loaded.template().subjectType() != selection.subjectType()) {
            throw new IllegalArgumentException("template subject type does not match run subject type");
        }
        validatePurposeAuthority(request, loaded, selection);
        validateBaseline(request, selection);
        Instant now = clock.instant();
        RubricsRunEntity run = newRun(request, loaded, selection, now);
        run.setEvaluatorVersion(expectedEvaluatorVersion(loaded));
        run.setAdmissionKey(admissionKey);
        try {
            runs.insert(run);
        } catch (DataIntegrityViolationException error) {
            if (admissionKey == null) {
                throw error;
            }
            RubricsRunEntity concurrent = runs.findByAdmissionKey(admissionKey);
            if (concurrent == null) {
                throw error;
            }
            return new EvaluationRunId(concurrent.getId());
        }
        for (SelectedSubject subject : selection.subjects()) {
            items.insert(newItem(run.getId(), selection.subjectType(), subject, now));
        }
        return new EvaluationRunId(run.getId());
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
            LOGGER.info("Rubrics item started runId={} subjectType={} subjectId={} template={}:{} evaluator={}",
                    run.getId(), item.getSubjectType(), item.getSubjectId(),
                    run.getTemplateId(), run.getTemplateVersion(), run.getEvaluatorVersion());
            EvaluationSubject subject = subjects.resolve(
                    loaded.template().subjectType(), item.getSubjectId());
            if (item.getSubjectSnapshotHash() != null
                    && !Objects.equals(item.getSubjectSnapshotHash(), subject.snapshotHash())) {
                // Dataset 绑定的是不可变 owner snapshot；漂移项不能调用 judge，也不能伪装成质量失败。
                claims.finishNonReplayable(
                        claim, "SUBJECT_SNAPSHOT_MISMATCH", clock.instant());
                metrics.recordItem(item.getSubjectType(), "PARTIAL",
                        Duration.between(claimedAt, clock.instant()));
                return;
            }
            PreparedEvaluation prepared = evaluate(
                    loaded, subject, () -> heartbeat(claim));
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
            metrics.recordItem(item.getSubjectType(), prepared.summary().inconclusiveCount() > 0
                    ? "PARTIAL" : "SUCCEEDED", Duration.between(claimedAt, clock.instant()));
        } catch (IllegalArgumentException error) {
            // Subject/schema 等不可恢复错误成为 item 失败；局部 provider 错误已在 criterion 内隔离。
            claims.finish(
                    claim,
                    item.getSubjectSnapshotHash(),
                    RunItemStatus.FAILED,
                    clock.instant());
            metrics.recordItem(item.getSubjectType(), "FAILED",
                    Duration.between(claimedAt, clock.instant()));
        } catch (RuntimeException error) {
            // 数据库、序列化和瞬时依赖错误保留恢复资格，watchdog/resume 可在新 epoch 重试。
            claims.failRetryable(claim, error.getClass().getSimpleName(), clock.instant());
            metrics.recordItem(item.getSubjectType(), "FAILED_RETRYABLE",
                    Duration.between(claimedAt, clock.instant()));
        }
    }

    private PreparedEvaluation evaluate(
            LoadedTemplate loaded,
            EvaluationSubject subject,
            Runnable beforeRollout) {
        EvidencePack pack = evidence.build(subject);
        List<EvaluatedCriterion> evaluated = new ArrayList<>();
        String evaluatorVersion = "deterministic:" + loaded.canonicalHash();
        for (Criterion criterion : loaded.template().criteria()) {
            CriterionEvaluationResult result = evaluateCriterion(
                    criterion, loaded, subject, pack, beforeRollout);
            evaluatorVersion = result.evaluatorVersion() == null
                    ? evaluatorVersion : result.evaluatorVersion();
            evaluated.add(new EvaluatedCriterion(
                    criterion.key(), criterion.kind(), result.result(),
                    result.agreement(), result.rollouts()));
            metrics.recordCriterion(criterion.key(), result.result().verdict(),
                    result.result().reason(), result.agreement());
        }
        EvaluationSummary summary = summaries.calculate(evaluated.stream()
                .map(value -> new CriterionOutcome(
                        value.key(), value.kind(), value.result().verdict()))
                .toList());
        metrics.recordSummary(summary);
        return new PreparedEvaluation(subject, pack, summary, evaluated, evaluatorVersion);
    }

    private CriterionEvaluationResult evaluateCriterion(
            Criterion criterion,
            LoadedTemplate loaded,
            EvaluationSubject subject,
            EvidencePack pack,
            Runnable beforeRollout) {
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
                criterion, loaded.template().evaluator(), subject, pack, beforeRollout);
        return new CriterionEvaluationResult(
                judged.result(), judged.agreement(), judged.rollouts(), judged.evaluatorVersion());
    }

    private static CriterionEvaluationResult criterionResult(CriterionResult result) {
        return new CriterionEvaluationResult(result, null, List.of(), null);
    }

    private void heartbeat(RunItemClaim claim) {
        if (!claims.heartbeat(claim, clock.instant(), claimLease)) {
            throw new IllegalStateException("evaluation claim was lost before rollout");
        }
    }

    private void recomputeRun(RubricsRunEntity run) {
        RunStatus previousStatus = run.getStatus();
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
        if (finishedAt != null && previousStatus != RunStatus.SUCCEEDED
                && previousStatus != RunStatus.PARTIAL && previousStatus != RunStatus.FAILED) {
            metrics.recordRun(run.getPurpose().name(), status.name(),
                    Duration.between(run.getStartedAt(), finishedAt));
            LOGGER.info("Rubrics run finished runId={} template={}:{} evaluator={} status={}",
                    run.getId(), run.getTemplateId(), run.getTemplateVersion(),
                    run.getEvaluatorVersion(), status);
        }
        if (finishedAt != null
                && run.getPurpose() == com.pixflow.module.rubrics.api.RunPurpose.CALIBRATION) {
            LoadedTemplate loaded = templates.require(run.getTemplateId(), run.getTemplateVersion());
            calibrationReports.createIfComplete(run, loaded);
        }
        if (finishedAt != null
                && run.getPurpose()
                        == com.pixflow.module.rubrics.api.RunPurpose.FORMAL_REGRESSION
                && run.getBaselineRunId() != null) {
            regressionAlerts.createIfRegressed(
                    run,
                    regressionReports.compare(run.getId(), run.getBaselineRunId()),
                    clock.instant());
        }
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
                        reportFacts(run, all),
                        complete));
    }

    private Map<String, Object> reportFacts(
            RubricsRunEntity run, List<RubricsRunItemEntity> all) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nonReplayableCount", countNonReplayable(all));
        if (run.getPurpose() == com.pixflow.module.rubrics.api.RunPurpose.CALIBRATION) {
            facts.putAll(calibrationReports.findByRunId(run.getId()));
        }
        if (run.getPurpose()
                == com.pixflow.module.rubrics.api.RunPurpose.FORMAL_REGRESSION) {
            if (run.getBaselineRunId() == null) {
                facts.put("formalBaseline", true);
            } else {
                facts.putAll(regressionReports.compare(
                        run.getId(), run.getBaselineRunId()));
            }
        }
        return Map.copyOf(facts);
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
                dataset.version(),
                dataset.databaseId());
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

    private void validatePurposeAuthority(
            EvaluationRunRequest request,
            LoadedTemplate loaded,
            ResolvedSelection selection) {
        if (request.purpose()
                != com.pixflow.module.rubrics.api.RunPurpose.FORMAL_REGRESSION) {
            return;
        }
        if (loaded.template().lifecycle() == TemplateLifecycle.EXPERIMENTAL) {
            throw new IllegalArgumentException(
                    "experimental template cannot run a formal regression");
        }
        String evaluatorVersion = expectedEvaluatorVersion(loaded);
        if (selection.datasetDatabaseId() == null
                || !validationReports.hasPassingReport(
                        selection.datasetDatabaseId(),
                        loaded.template().id(),
                        loaded.template().version(),
                        loaded.canonicalHash(),
                        evaluatorVersion)) {
            throw new IllegalArgumentException(
                    "formal regression requires a passing validation report for the exact release");
        }
    }

    private String expectedEvaluatorVersion(LoadedTemplate loaded) {
        boolean usesLlm = loaded.template().criteria().stream()
                .anyMatch(criterion -> criterion.verifier().type() == VerifierType.LLM);
        return usesLlm
                ? llm.expectedEvaluatorVersion(loaded.template().evaluator())
                : "deterministic:" + loaded.canonicalHash();
    }

    private record CriterionEvaluationResult(
            CriterionResult result,
            Double agreement,
            List<JudgeRollout> rollouts,
            String evaluatorVersion) {
    }

    private record PreparedEvaluation(
            EvaluationSubject subject,
            EvidencePack pack,
            EvaluationSummary summary,
            List<EvaluatedCriterion> criteria,
            String evaluatorVersion) {
    }

    private record ResolvedSelection(
            SubjectType subjectType,
            List<SelectedSubject> subjects,
            String datasetId,
            String datasetVersion,
            Long datasetDatabaseId) {
    }

    private record SelectedSubject(
            String id,
            String expectedSnapshotHash,
            boolean replayable,
            String replayError) {
    }
}
