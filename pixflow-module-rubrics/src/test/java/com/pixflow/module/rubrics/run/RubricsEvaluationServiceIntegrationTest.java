package com.pixflow.module.rubrics.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.EvaluationRunStatus;
import com.pixflow.module.rubrics.api.DatasetSelection;
import com.pixflow.module.rubrics.api.ExplicitSubjects;
import com.pixflow.module.rubrics.api.RunPurpose;
import com.pixflow.module.rubrics.api.RunTrigger;
import com.pixflow.module.rubrics.api.TemplateRef;
import com.pixflow.module.rubrics.evidence.ImageEvidencePackBuilder;
import com.pixflow.module.rubrics.judge.MajorityVerdictReducer;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultMapper;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationMapper;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver;
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
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

    @BeforeEach
    void createSchema() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
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
        session = factory.openSession(true);
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
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
        var service = service();

        var baseline = service.start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.CALIBRATION,
                RunTrigger.MANUAL,
                new DatasetSelection("image-holdout", "1.0.0"),
                null));
        // formal baseline 的授权由离线 baseline 工作流产生；测试在此直接固化该事实。
        jdbc.update(
                "update rubrics_run set purpose = 'FORMAL_REGRESSION' where id = ?",
                baseline.id().value());
        session.clearCache();
        var regression = service.start(new EvaluationRunRequest(
                new TemplateRef("image-rules", "1.0.0"),
                RunPurpose.FORMAL_REGRESSION,
                RunTrigger.MANUAL,
                new DatasetSelection("image-holdout", "1.0.0"),
                baseline.id()));

        assertThat(baseline.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(regression.status()).isEqualTo(EvaluationRunStatus.SUCCEEDED);
        assertThat(regression.datasetId()).isEqualTo("image-holdout");
        assertThat(regression.datasetVersion()).isEqualTo("1.0.0");
        assertThat(regression.baselineRunId()).isEqualTo(baseline.id());
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
                RunPurpose.CALIBRATION,
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

    private DefaultRubricsEvaluationService service() {
        RubricsRunMapper runs = session.getMapper(RubricsRunMapper.class);
        RubricsRunItemMapper items = session.getMapper(RubricsRunItemMapper.class);
        RubricsEvaluationMapper evaluations = session.getMapper(RubricsEvaluationMapper.class);
        RubricsCriterionResultMapper criteria = session.getMapper(RubricsCriterionResultMapper.class);
        RubricsJudgeRolloutMapper rollouts = session.getMapper(RubricsJudgeRolloutMapper.class);
        RunItemClaimRepository claims = new RunItemClaimRepository(jdbc);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EvaluationPersistence persistence = new EvaluationPersistence(
                evaluations, criteria, rollouts, claims, mapper);
        return new DefaultRubricsEvaluationService(
                templates(),
                new ImageSubjectSnapshotResolver(outcomes()),
                evidence(mapper),
                new RuleCriterionVerifier(),
                new RepeatedLlmCriterionVerifier(
                        mock(ChatModelClient.class),
                        mock(VisionModelClient.class),
                        mock(ModelRouter.class),
                        mapper,
                        entry -> null,
                        new MajorityVerdictReducer()),
                new EvaluationSummaryCalculator(),
                persistence,
                runs,
                items,
                claims,
                new EvaluationDatasetRepository(jdbc),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
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
                TemplateLifecycle.EXPERIMENTAL,
                new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_VISION, 3, "1"),
                List.of(resolution, format));
        return new TemplateRegistry(List.of(new LoadedTemplate(
                template, "a".repeat(64), "integration-test")));
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
                        4,
                        NOW));
            }

            @Override
            public List<SuccessfulResultSnapshot> successfulResults(long taskId) {
                return List.of();
            }
        };
    }

    private static ImageEvidencePackBuilder evidence(ObjectMapper mapper) {
        byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.getBytes(ObjectLocation.of(BucketType.GENERATED, "images/91.png")))
                .thenReturn(bytes);
        PublishedAssetReader.PublishedAssetContent content =
                new PublishedAssetReader.PublishedAssetContent(
                    91,
                    ObjectLocation.of(BucketType.GENERATED, "images/91.png"),
                    "image/png",
                    bytes.length);
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
                storage, published, codec, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
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
