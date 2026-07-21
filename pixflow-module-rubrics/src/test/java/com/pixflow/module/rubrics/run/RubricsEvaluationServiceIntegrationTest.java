package com.pixflow.module.rubrics.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.EvaluationRunStatus;
import com.pixflow.module.rubrics.api.DatasetSelection;
import com.pixflow.module.rubrics.api.ExplicitSubjects;
import com.pixflow.module.rubrics.api.RunPurpose;
import com.pixflow.module.rubrics.api.RunTrigger;
import com.pixflow.module.rubrics.api.TemplateRef;
import com.pixflow.module.rubrics.evidence.ImageEvidencePackBuilder;
import com.pixflow.module.rubrics.evidence.TextEvidencePackBuilder;
import com.pixflow.module.rubrics.evidence.EvidencePackFactory;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.judge.MajorityVerdictReducer;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultMapper;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationMapper;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver;
import com.pixflow.module.rubrics.subject.TextSubjectSnapshotResolver;
import com.pixflow.module.rubrics.subject.EvaluationSubjectCatalog;
import com.pixflow.module.rubrics.summary.EvaluationSummaryCalculator;
import com.pixflow.module.rubrics.template.Applicability;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.EvaluatorSpec;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.rubrics.template.VerifierSpec;
import com.pixflow.module.rubrics.template.VerifierType;
import com.pixflow.module.rubrics.verifier.RuleCriterionVerifier;
import com.pixflow.module.rubrics.verifier.CriterionResult;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import org.apache.ibatis.session.SqlSession;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class RubricsEvaluationServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private SqlSession session;

    private JdbcTemplate jdbc;

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void createSchema() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        dropSchema();
        applySchema();

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        var factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(RubricsRunMapper.class);
        factory.getConfiguration().addMapper(RubricsRunItemMapper.class);
        factory.getConfiguration().addMapper(RubricsEvaluationMapper.class);
        factory.getConfiguration().addMapper(RubricsCriterionResultMapper.class);
        factory.getConfiguration().addMapper(RubricsJudgeRolloutMapper.class);
        session = new SqlSessionTemplate(factory);
    }

    @AfterEach
    void closeSession() {
        if (session != null && !(session instanceof SqlSessionTemplate)) {
            session.close();
        }
    }

    @Test
    void startsImageRunAndReturnsPersistedBoundedView() {
        var service = service();

        var view = service.start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.MANUAL_INSPECTION,
                RunTrigger.MANUAL,
                new ExplicitSubjects(SubjectType.IMAGE_RESULT, List.of("41")),
                null));

        assertThat(view.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(view.totalCount()).isEqualTo(1);
        assertThat(view.succeededCount()).isEqualTo(1);
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.subjectId()).isEqualTo("41");
            assertThat(item.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
            assertThat(item.qualityGate()).isEqualTo(QualityGate.PASSED);
            assertThat(item.coverage()).isEqualTo(1.0);
        });
        assertThat(view.report().complete()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from rubrics_criterion_result", Integer.class))
                .isEqualTo(2);
        assertThat(service.get(view.id())).isEqualTo(view);
    }

    @Test
    void registersDatasetManifestIdempotentlyAndRejectsIdentityDrift() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DatasetManifestRegistrar registrar = new DatasetManifestRegistrar(
                jdbc, mapper,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        DatasetManifestRegistrar.DatasetManifest manifest =
                new DatasetManifestRegistrar.DatasetManifest(
                        "immutable-images", "1.0.0", SubjectType.IMAGE_RESULT,
                        "release holdout", "1", "1", List.of(
                        new DatasetManifestRegistrar.DatasetItem(
                                "41", "a".repeat(64), "HOLDOUT", "catalog", "EASY",
                                true, null)));

        long first = registrar.register(manifest);
        long duplicate = registrar.register(manifest);

        assertThat(duplicate).isEqualTo(first);
        assertThatThrownBy(() -> registrar.register(
                new DatasetManifestRegistrar.DatasetManifest(
                        "immutable-images", "1.0.0", SubjectType.IMAGE_RESULT,
                        "changed content", "1", "1", manifest.items())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void persistsIdempotentAutomationAdmissionBeforeEvaluationRuns() {
        var service = service();
        var admissions = new RunAdmissionService(service);
        var request = new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.PRODUCTION_SAMPLE,
                RunTrigger.EVENT_DRIVEN,
                new ExplicitSubjects(SubjectType.IMAGE_RESULT, List.of("41")),
                null);

        var first = admissions.admit(request, "task-completed:7:image-rules:1.0.0");
        var duplicate = admissions.admit(request, "task-completed:7:image-rules:1.0.0");

        assertThat(duplicate).isEqualTo(first);
        assertThat(service.get(first).status()).isEqualTo(EvaluationRunStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from rubrics_run", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isZero();
        assertThat(service.resume(first).status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
    }

    @Test
    void evaluatesCopyAndDecisionThroughTaskPublicSnapshots() {
        var service = service();

        var copy = service.start(new EvaluationRunRequest(
                new TemplateRef("copy-quality", "1.0.0"),
                RunPurpose.MANUAL_INSPECTION,
                RunTrigger.MANUAL,
                new ExplicitSubjects(SubjectType.COPY_RESULT, List.of("51")),
                null));
        var decision = service.start(new EvaluationRunRequest(
                new TemplateRef("decision-quality", "1.0.0"),
                RunPurpose.MANUAL_INSPECTION,
                RunTrigger.MANUAL,
                new ExplicitSubjects(
                        SubjectType.TASK_DECISION, List.of("7@decision-revision")),
                null));

        assertThat(copy.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(copy.items()).singleElement().satisfies(item ->
                assertThat(item.qualityGate()).isEqualTo(QualityGate.PASSED));
        assertThat(decision.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(decision.items()).singleElement().satisfies(item ->
                assertThat(item.qualityGate()).isEqualTo(QualityGate.PASSED));
        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void formalRegressionRequiresAndPreservesTheSameDatasetIdentity() {
        String snapshotHash = new ImageSubjectSnapshotResolver(outcomes())
                .resolve("41")
                .snapshotHash();
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, manifest_hash)
                values (?, ?, ?, ?)
                """, "image-holdout", "1.0.0", "IMAGE_RESULT", "b".repeat(64));
        long datasetPk = jdbc.queryForObject(
                "select id from rubrics_dataset where dataset_id = 'image-holdout'",
                Long.class);
        jdbc.update("""
                insert into rubrics_dataset_item(
                  dataset_pk, subject_id, subject_snapshot_hash, partition_name, replayable)
                values (?, ?, ?, ?, true)
                """, datasetPk, "41", snapshotHash, "HOLDOUT");
        jdbc.update("""
                insert into rubrics_validation_report(
                  run_id, dataset_pk, template_id, template_version, template_hash,
                  evaluator_version, evidence_schema_version, report_json, thresholds_met)
                values (?, ?, ?, ?, ?, ?, ?, ?, true)
                """,
                -1,
                datasetPk,
                "image-rules",
                "1.0.0",
                "a".repeat(64),
                "deterministic:" + "a".repeat(64),
                "1",
                "{}");
        jdbc.update("""
                insert into rubrics_validation_report(
                  run_id, dataset_pk, template_id, template_version, template_hash,
                  evaluator_version, evidence_schema_version, report_json, thresholds_met)
                values (?, ?, ?, ?, ?, ?, ?, ?, true)
                """,
                -2,
                datasetPk,
                "image-rules",
                "2.0.0",
                "b".repeat(64),
                "deterministic:" + "b".repeat(64),
                "1",
                "{}");
        var service = service();

        var baseline = service.start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.FORMAL_REGRESSION,
                RunTrigger.MANUAL,
                new DatasetSelection("image-holdout", "1.0.0"),
                null));
        assertThat(baseline.report().facts()).containsEntry("formalBaseline", true);
        var regression = service.start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "2.0.0"),
                RunPurpose.FORMAL_REGRESSION,
                RunTrigger.MANUAL,
                new DatasetSelection("image-holdout", "1.0.0"),
                baseline.id()));

        assertThat(baseline.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(regression.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(regression.datasetId()).isEqualTo("image-holdout");
        assertThat(regression.datasetVersion()).isEqualTo("1.0.0");
        assertThat(regression.baselineRunId()).isEqualTo(baseline.id());
        assertThat(regression.report().facts())
                .containsEntry("pairedSubjectCount", 1)
                .containsEntry("passToFailCount", 1)
                .containsEntry("failToPassCount", 0);
        assertThat(regression.report().facts().get("passToFailByCriterion"))
                .isEqualTo(Map.of("resolution", 1));
        assertThat(jdbc.queryForObject("select count(*) from rubrics_alert", Integer.class))
                .isEqualTo(1);
        service.resume(regression.id());
        assertThat(jdbc.queryForObject("select count(*) from rubrics_alert", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void calibrationBuildsValidationReportFromIndependentHoldoutLabels() {
        String snapshotHash = new ImageSubjectSnapshotResolver(outcomes())
                .resolve("41")
                .snapshotHash();
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, manifest_hash, gold_label_version)
                values (?, ?, ?, ?, ?)
                """, "calibration-holdout", "1.0.0", "IMAGE_RESULT", "c".repeat(64), "1");
        long datasetPk = jdbc.queryForObject(
                "select id from rubrics_dataset where dataset_id = 'calibration-holdout'",
                Long.class);
        jdbc.update("""
                insert into rubrics_dataset_item(
                  dataset_pk, subject_id, subject_snapshot_hash, partition_name, replayable,
                  category_name, difficulty)
                values (?, ?, ?, 'HOLDOUT', true, 'catalog', 'EASY')
                """, datasetPk, "41", snapshotHash);
        GoldLabelRepository labels = new GoldLabelRepository(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        labels.importLabels("calibration-holdout", "1.0.0", List.of(
                new GoldLabelRepository.GoldLabel(
                        "41", "resolution", "human-a",
                        com.pixflow.module.rubrics.model.CriterionVerdict.PASS, false),
                new GoldLabelRepository.GoldLabel(
                        "41", "resolution", "human-b",
                        com.pixflow.module.rubrics.model.CriterionVerdict.PASS, false),
                new GoldLabelRepository.GoldLabel(
                        "41", "format", "human-a",
                        com.pixflow.module.rubrics.model.CriterionVerdict.PASS, false)));

        DefaultRubricsEvaluationService service = service();
        EvaluationRunRequest request = new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.CALIBRATION,
                RunTrigger.MANUAL,
                new DatasetSelection("calibration-holdout", "1.0.0"),
                null);
        assertThatThrownBy(() -> service.start(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two independent annotators");
        long runId = jdbc.queryForObject("select id from rubrics_run", Long.class);
        labels.importLabels("calibration-holdout", "1.0.0", List.of(
                new GoldLabelRepository.GoldLabel(
                        "41", "format", "human-b",
                        com.pixflow.module.rubrics.model.CriterionVerdict.PASS, false)));

        var report = service.resume(new EvaluationRunId(runId));

        assertThat(report.report().facts()).containsEntry("thresholdsMet", true);
        assertThat(jdbc.queryForObject(
                "select count(*) from rubrics_validation_report where thresholds_met = true",
                Integer.class)).isEqualTo(1);
        assertThat(new ValidationReportRepository(jdbc).hasPassingReportForRelease(
                "image-rules", "1.0.0", "a".repeat(64),
                "deterministic:" + "a".repeat(64))).isTrue();
    }

    @Test
    void rejectsFormalBootstrapWithoutAnExactPassingValidationReport() {
        String snapshotHash = new ImageSubjectSnapshotResolver(outcomes())
                .resolve("41")
                .snapshotHash();
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, manifest_hash)
                values (?, ?, ?, ?)
                """, "unvalidated-images", "1.0.0", "IMAGE_RESULT", "9".repeat(64));
        long datasetPk = jdbc.queryForObject(
                "select id from rubrics_dataset where dataset_id = 'unvalidated-images'",
                Long.class);
        jdbc.update("""
                insert into rubrics_dataset_item(
                  dataset_pk, subject_id, subject_snapshot_hash, partition_name, replayable)
                values (?, ?, ?, ?, true)
                """, datasetPk, "41", snapshotHash, "HOLDOUT");

        assertThatThrownBy(() -> service().start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.FORMAL_REGRESSION,
                RunTrigger.MANUAL,
                new DatasetSelection("unvalidated-images", "1.0.0"),
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation report");

        assertThat(jdbc.queryForObject("select count(*) from rubrics_run", Integer.class))
                .isZero();
    }

    @Test
    void reportsNonReplayableDatasetItemsWithoutTreatingThemAsRunFailures() {
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, manifest_hash)
                values (?, ?, ?, ?)
                """, "missing-image", "1.0.0", "IMAGE_RESULT", "c".repeat(64));
        long datasetPk = jdbc.queryForObject(
                "select id from rubrics_dataset where dataset_id = 'missing-image'",
                Long.class);
        jdbc.update("""
                insert into rubrics_dataset_item(
                  dataset_pk, subject_id, subject_snapshot_hash, partition_name,
                  replayable, replay_error)
                values (?, ?, ?, ?, false, ?)
                """, datasetPk, "404", "d".repeat(64), "HOLDOUT", "OBJECT_MISSING");

        var view = service().start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.MANUAL_INSPECTION,
                RunTrigger.MANUAL,
                new DatasetSelection("missing-image", "1.0.0"),
                null));

        assertThat(view.status()).isEqualTo(EvaluationRunStatus.PARTIAL);
        assertThat(view.failedCount()).isZero();
        assertThat(view.partialCount()).isEqualTo(1);
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.replayable()).isFalse();
            assertThat(item.replayErrorCode()).isEqualTo("OBJECT_MISSING");
        });
        assertThat(view.report().facts()).containsEntry("nonReplayableCount", 1);
        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isZero();
    }

    @Test
    void isolatesDatasetItemWhenTheOwnerSnapshotHasDrifted() {
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, manifest_hash)
                values (?, ?, ?, ?)
                """, "drifted-image", "1.0.0", "IMAGE_RESULT", "e".repeat(64));
        long datasetPk = jdbc.queryForObject(
                "select id from rubrics_dataset where dataset_id = 'drifted-image'",
                Long.class);
        jdbc.update("""
                insert into rubrics_dataset_item(
                  dataset_pk, subject_id, subject_snapshot_hash, partition_name, replayable)
                values (?, ?, ?, ?, true)
                """, datasetPk, "41", "f".repeat(64), "HOLDOUT");

        var view = service().start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.MANUAL_INSPECTION,
                RunTrigger.MANUAL,
                new DatasetSelection("drifted-image", "1.0.0"),
                null));

        assertThat(view.status()).isEqualTo(EvaluationRunStatus.PARTIAL);
        assertThat(view.failedCount()).isZero();
        assertThat(view.partialCount()).isEqualTo(1);
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.replayable()).isFalse();
            assertThat(item.replayErrorCode()).isEqualTo("SUBJECT_SNAPSHOT_MISMATCH");
        });
        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isZero();
    }

    @Test
    void rollsBackAllEvaluationFactsWhenTheClaimFenceIsLost() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RunItemClaimRepository claims = new RunItemClaimRepository(jdbc);
        EvaluationPersistence persistence = new EvaluationPersistence(
                session.getMapper(RubricsEvaluationMapper.class),
                session.getMapper(RubricsCriterionResultMapper.class),
                session.getMapper(RubricsJudgeRolloutMapper.class),
                claims,
                mapper,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        var subject = new ImageSubjectSnapshotResolver(outcomes()).resolve("41");
        var pack = EvidencePack.create(subject.snapshotHash(), List.of(new EvidenceEntry(
                "E1",
                EvidenceType.IMAGE_METADATA,
                "IMAGE:91",
                "a".repeat(64),
                NOW,
                Map.of("width", 1024, "height", 1024))));
        var result = new CriterionResult(
                com.pixflow.module.rubrics.model.CriterionVerdict.PASS,
                VerdictReason.RULE_MATCH,
                "image dimensions satisfy the rule",
                List.of("E1"),
                Map.of());
        var evaluated = List.of(new EvaluatedCriterion(
                "resolution", CriterionKind.HARD_RULE, result, null, List.of()));
        var summary = new EvaluationSummaryCalculator().calculate(List.of(
                new com.pixflow.module.rubrics.summary.CriterionOutcome(
                        "resolution",
                        CriterionKind.HARD_RULE,
                        com.pixflow.module.rubrics.model.CriterionVerdict.PASS)));

        assertThatThrownBy(() -> persistence.save(
                999,
                new RunItemClaim(999, 1, "lost-worker", NOW.plusSeconds(60)),
                templates().require("image-rules", "1.0.0"),
                "evaluator-v1",
                subject,
                pack,
                summary,
                evaluated,
                NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persist rubric evaluation");

        assertThat(jdbc.queryForObject("select count(*) from rubrics_evaluation", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from rubrics_criterion_result", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from rubrics_judge_rollout", Integer.class))
                .isZero();
    }

    private DefaultRubricsEvaluationService service() {
        RubricsRunMapper runs = session.getMapper(RubricsRunMapper.class);
        RubricsRunItemMapper items = session.getMapper(RubricsRunItemMapper.class);
        RubricsEvaluationMapper evaluations = session.getMapper(RubricsEvaluationMapper.class);
        RubricsCriterionResultMapper criteria = session.getMapper(RubricsCriterionResultMapper.class);
        RubricsJudgeRolloutMapper rollouts = session.getMapper(RubricsJudgeRolloutMapper.class);
        RunItemClaimRepository claims = new RunItemClaimRepository(jdbc);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EvaluationPersistence persistence = new EvaluationPersistence(
                evaluations,
                criteria,
                rollouts,
                claims,
                mapper,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        return new DefaultRubricsEvaluationService(
                templates(),
                new EvaluationSubjectCatalog(
                        new ImageSubjectSnapshotResolver(outcomes()),
                        new TextSubjectSnapshotResolver(outcomes())),
                new EvidencePackFactory(
                        evidence(mapper),
                        new TextEvidencePackBuilder(
                                Clock.fixed(NOW, ZoneOffset.UTC),
                                // 集成测试不依赖真实 trace：返回空，使 decision pack 仅含 DAG_SNAPSHOT（与历史行为一致）。
                                subject -> List.of())),
                new RuleCriterionVerifier(),
                new RepeatedLlmCriterionVerifier(
                        passingTextJudge(),
                        mock(VisionModelClient.class),
                        role -> new ResolvedModel(
                                role,
                                "judge-provider",
                                "judge-model",
                                ModelCapability.CHAT,
                                0.0,
                                512,
                                Duration.ofSeconds(5)),
                        mapper,
                        entry -> null,
                        new MajorityVerdictReducer(),
                        new RubricsMetrics(new SimpleMeterRegistry()),
                        2000),
                new EvaluationSummaryCalculator(),
                persistence,
                runs,
                items,
                claims,
                new EvaluationDatasetRepository(jdbc),
                new ValidationReportRepository(jdbc),
                new CalibrationReportRepository(jdbc, mapper),
                new RegressionReportRepository(jdbc),
                new RegressionAlertRepository(jdbc, mapper),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new RubricsMetrics(new SimpleMeterRegistry()));
    }

    private static TemplateRegistry templates() {
        Criterion resolution = new Criterion(
                "resolution",
                CriterionKind.HARD_RULE,
                "The image is large enough.",
                "Both dimensions are at least 800 pixels.",
                "A dimension is smaller than 800 pixels.",
                Set.of(EvidenceType.IMAGE_METADATA),
                Applicability.ALWAYS,
                new VerifierSpec(
                        VerifierType.RULE,
                        "resolution",
                        null,
                        Map.of("minWidth", 800, "minHeight", 800)));
        Criterion format = new Criterion(
                "format",
                CriterionKind.HARD_RULE,
                "The image uses an approved format.",
                "The image is PNG.",
                "The image is not PNG.",
                Set.of(EvidenceType.IMAGE_METADATA),
                Applicability.ALWAYS,
                new VerifierSpec(
                        VerifierType.RULE,
                        "format",
                        null,
                        Map.of("allowed", List.of("PNG"))));
        RubricTemplate template = new RubricTemplate(
                "image-rules",
                "1.0.0",
                "Image rules",
                SubjectType.IMAGE_RESULT,
                TemplateLifecycle.VALIDATED,
                new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_VISION, 3, "1"),
                List.of(resolution, format));
        Criterion strictResolution = new Criterion(
                "resolution",
                CriterionKind.HARD_RULE,
                "The image is large enough for the strict release.",
                "Both dimensions are at least 1200 pixels.",
                "A dimension is smaller than 1200 pixels.",
                Set.of(EvidenceType.IMAGE_METADATA),
                Applicability.ALWAYS,
                new VerifierSpec(
                        VerifierType.RULE,
                        "resolution",
                        null,
                        Map.of("minWidth", 1200, "minHeight", 1200)));
        RubricTemplate strictTemplate = new RubricTemplate(
                "image-rules",
                "2.0.0",
                "Strict image rules",
                SubjectType.IMAGE_RESULT,
                TemplateLifecycle.VALIDATED,
                new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_VISION, 3, "1"),
                List.of(strictResolution, format));
        Criterion copyCriterion = new Criterion(
                "copy_grounded",
                CriterionKind.PRINCIPLE,
                "The copy is grounded in the supplied text.",
                "The copy contains a concrete product statement.",
                "The copy is empty or unrelated.",
                Set.of(EvidenceType.COPY_TEXT),
                Applicability.ALWAYS,
                new VerifierSpec(VerifierType.LLM, null, null, Map.of()));
        RubricTemplate copyTemplate = new RubricTemplate(
                "copy-quality",
                "1.0.0",
                "Copy quality",
                SubjectType.COPY_RESULT,
                TemplateLifecycle.EXPERIMENTAL,
                new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_TEXT, 3, "1"),
                List.of(copyCriterion));
        Criterion decisionCriterion = new Criterion(
                "decision_grounded",
                CriterionKind.PRINCIPLE,
                "The decision has a concrete proposal.",
                "The proposal contains an executable payload.",
                "The proposal payload is absent.",
                Set.of(EvidenceType.PROPOSAL),
                Applicability.ALWAYS,
                new VerifierSpec(VerifierType.LLM, null, null, Map.of()));
        RubricTemplate decisionTemplate = new RubricTemplate(
                "decision-quality",
                "1.0.0",
                "Decision quality",
                SubjectType.TASK_DECISION,
                TemplateLifecycle.EXPERIMENTAL,
                new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_TEXT, 3, "1"),
                List.of(decisionCriterion));
        return new TemplateRegistry(List.of(
                new LoadedTemplate(template, "a".repeat(64), "integration-test"),
                new LoadedTemplate(strictTemplate, "b".repeat(64), "integration-test"),
                new LoadedTemplate(copyTemplate, "c".repeat(64), "integration-test"),
                new LoadedTemplate(decisionTemplate, "d".repeat(64), "integration-test")));
    }

    private static TaskOutcomeQuery outcomes() {
        return new TaskOutcomeQuery() {
            @Override
            public Optional<SuccessfulResultSnapshot> successfulResult(long resultId) {
                return Optional.of(new SuccessfulResultSnapshot(
                        resultId,
                        7,
                        "REDRAW",
                        "image-1",
                        "sku-1",
                        "group-1",
                        "front",
                        "branch-1",
                        91,
                        "IMAGE:91",
                        5,
                        "provider",
                        "model",
                        NOW));
            }

            @Override
            public List<SuccessfulResultSnapshot> successfulResults(long taskId) {
                return List.of();
            }

            @Override
            public Optional<CopyResultSnapshot> successfulCopy(long resultId) {
                return resultId == 51
                        ? Optional.of(new CopyResultSnapshot(
                                resultId,
                                7,
                                "A clean product description.",
                                "producer-provider",
                                "producer-model",
                                NOW))
                        : Optional.empty();
            }

            @Override
            public Optional<ConfirmedDecisionSnapshot> confirmedDecision(
                    long taskId, String revision) {
                return taskId == 7 && "decision-revision".equals(revision)
                        ? Optional.of(new ConfirmedDecisionSnapshot(
                                taskId,
                                "IMAGE_PROCESS",
                                "conversation-1",
                                3L,
                                "{\"nodes\":[]}",
                                "{\"nodes\":[]}",
                                revision,
                                "1.0",
                                NOW))
                        : Optional.empty();
            }
        };
    }

    private static ChatModelClient passingTextJudge() {
        return new ChatModelClient() {
            @Override
            public ChatResult call(ChatRequest request) {
                return new ChatResult(
                        "{\"verdict\":\"PASS\",\"rationale\":\"supported by E1\","
                                + "\"evidenceIds\":[\"E1\"]}",
                        List.of(),
                        StopReason.STOP,
                        new TokenUsage(10, 5, 15));
            }

            @Override
            public Flux<ChatStreamEvent> stream(ChatRequest request) {
                return Flux.empty();
            }
        };
    }

    private static ImageEvidencePackBuilder evidence(ObjectMapper mapper) {
        byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
        PublishedAssetReader.PublishedAssetContent content =
                new PublishedAssetReader.PublishedAssetContent(
                    91,
                    "image/png",
                    sha256(bytes),
                    bytes.length,
                    new PublishedAssetReader.ContentAccess() {
                        @Override
                        public java.io.InputStream open() {
                            return new java.io.ByteArrayInputStream(bytes);
                        }

                        @Override
                        public java.net.URL presign(java.time.Duration ttl) {
                            return null;
                        }
                    });
        PublishedAssetReader published = new PublishedAssetReader() {
            public Optional<PublishedAssetContent> find(String referenceKey) {
                return Optional.of(content);
            }

            @Override
            public PublishedAssetContent require(String referenceKey) {
                return content;
            }
        };
        ImageCodec codec = mock(ImageCodec.class);
        when(codec.probe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ImageProbe(ImageFormat.PNG, 1024, 1024, true));
        return new ImageEvidencePackBuilder(
                published, codec, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void applySchema() throws Exception {
        String schema;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V1__create_rubrics_evaluation_facts.sql")) {
            if (stream == null) {
                throw new IllegalStateException("rubrics schema resource is missing");
            }
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private void dropSchema() {
        List<String> tables = List.of(
                "rubrics_judge_rollout",
                "rubrics_criterion_result",
                "rubrics_evaluation",
                "rubrics_run_item",
                "rubrics_alert",
                "rubrics_validation_report",
                "rubrics_gold_label",
                "rubrics_dataset_item",
                "rubrics_dataset",
                "rubrics_run");
        tables.forEach(table -> jdbc.execute("drop table if exists " + table));
    }
}
