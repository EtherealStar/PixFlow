package com.pixflow.module.rubrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.automation.AutomationAdmissionPolicy;
import com.pixflow.module.rubrics.automation.TaskCompletedEvaluationListener;
import com.pixflow.module.rubrics.automation.RubricsRecoveryScheduler;
import com.pixflow.module.rubrics.automation.RubricsScheduledDatasetRunner;
import com.pixflow.module.rubrics.evidence.ImageEvidencePackBuilder;
import com.pixflow.module.rubrics.evidence.TextEvidencePackBuilder;
import com.pixflow.module.rubrics.evidence.EvidencePackFactory;
import com.pixflow.module.rubrics.evidence.TraceEvidenceProvider;
import com.pixflow.module.rubrics.evidence.DefaultTraceEvidenceProvider;
import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.module.rubrics.judge.MajorityVerdictReducer;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultMapper;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationMapper;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.run.EvaluationPersistence;
import com.pixflow.module.rubrics.run.DefaultRubricsEvaluationService;
import com.pixflow.module.rubrics.run.EvaluationDatasetRepository;
import com.pixflow.module.rubrics.run.RunItemClaimRepository;
import com.pixflow.module.rubrics.run.RegressionReportRepository;
import com.pixflow.module.rubrics.run.RegressionAlertRepository;
import com.pixflow.module.rubrics.run.ValidationReportRepository;
import com.pixflow.module.rubrics.run.CalibrationReportRepository;
import com.pixflow.module.rubrics.run.GoldLabelRepository;
import com.pixflow.module.rubrics.run.DatasetManifestRegistrar;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.run.RunAdmissionService;
import com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver;
import com.pixflow.module.rubrics.subject.TextSubjectSnapshotResolver;
import com.pixflow.module.rubrics.subject.EvaluationSubjectCatalog;
import com.pixflow.module.rubrics.summary.EvaluationSummaryCalculator;
import com.pixflow.module.rubrics.template.TemplateLoader;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.rubrics.template.TemplateValidator;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.module.rubrics.verifier.RuleCriterionVerifier;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;

@AutoConfiguration
@EnableConfigurationProperties(RubricsProperties.class)
@EnableScheduling
@MapperScan(basePackageClasses = RubricsRunMapper.class, annotationClass = Mapper.class)
public class RubricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RubricsMetrics rubricsMetrics(MeterRegistry meterRegistry) {
        return new RubricsMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateValidator templateValidator(ObjectMapper objectMapper) {
        return new TemplateValidator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateLoader templateLoader(TemplateValidator validator) {
        return new TemplateLoader(validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateRegistry templateRegistry(
            TemplateLoader loader, RubricsProperties properties, ModelRouter modelRouter) {
        properties.validate();
        modelRouter.resolve(ModelRole.RUBRICS_JUDGE_TEXT);
        modelRouter.resolve(ModelRole.RUBRICS_JUDGE_VISION);
        return new TemplateRegistry(loader.load(
                properties.getTemplateScan().getClasspathPrefix(),
                properties.getTemplateScan().getUserHomeDir()));
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationSummaryCalculator evaluationSummaryCalculator() {
        return new EvaluationSummaryCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ImageSubjectSnapshotResolver imageSubjectSnapshotResolver(
            TaskOutcomeQuery query) {
        return new ImageSubjectSnapshotResolver(query);
    }

    @Bean
    @ConditionalOnMissingBean
    public TextSubjectSnapshotResolver textSubjectSnapshotResolver(TaskOutcomeQuery query) {
        return new TextSubjectSnapshotResolver(query);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceEvidenceProvider traceEvidenceProvider(TraceQuery traceQuery) {
        // TraceQuery 是生产必需依赖：缺失时 Spring 构造注入直接 fail fast，不静默少装 trace 能力。
        return new DefaultTraceEvidenceProvider(traceQuery, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    public TextEvidencePackBuilder textEvidencePackBuilder(TraceEvidenceProvider traces) {
        return new TextEvidencePackBuilder(Clock.systemUTC(), traces);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImageEvidencePackBuilder imageEvidencePackBuilder(
            PublishedAssetReader publishedAssets, ImageCodec codec,
            ObjectMapper objectMapper) {
        return new ImageEvidencePackBuilder(publishedAssets, codec, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationSubjectCatalog evaluationSubjectCatalog(
            ImageSubjectSnapshotResolver images, TextSubjectSnapshotResolver texts) {
        return new EvaluationSubjectCatalog(images, texts);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvidencePackFactory evidencePackFactory(
            ImageEvidencePackBuilder images, TextEvidencePackBuilder texts) {
        return new EvidencePackFactory(images, texts);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleCriterionVerifier ruleCriterionVerifier() {
        return new RuleCriterionVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public MajorityVerdictReducer majorityVerdictReducer() {
        return new MajorityVerdictReducer();
    }

    @Bean
    @ConditionalOnMissingBean
    public RepeatedLlmCriterionVerifier repeatedLlmCriterionVerifier(
            ChatModelClient chat, VisionModelClient vision, ModelRouter router, ObjectMapper mapper,
            PublishedAssetReader publishedAssets,
            MajorityVerdictReducer reducer, RubricsMetrics metrics,
            RubricsProperties properties) {
        return new RepeatedLlmCriterionVerifier(chat, vision, router, mapper, entry -> {
            return new ChatMessage.UrlImageContent(java.net.URI.create(
                    publishedAssets.require(entry.sourceRef())
                            .presign(Duration.ofMinutes(10)).toString()));
        }, reducer, metrics, properties.getMaxRationaleChars());
    }

    @Bean
    @ConditionalOnMissingBean
    public RunItemClaimRepository runItemClaimRepository(JdbcTemplate jdbcTemplate) {
        return new RunItemClaimRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationDatasetRepository evaluationDatasetRepository(JdbcTemplate jdbcTemplate) {
        return new EvaluationDatasetRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ValidationReportRepository validationReportRepository(JdbcTemplate jdbcTemplate) {
        return new ValidationReportRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GoldLabelRepository goldLabelRepository(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new GoldLabelRepository(
                jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean
    public DatasetManifestRegistrar datasetManifestRegistrar(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        return new DatasetManifestRegistrar(
                jdbcTemplate, objectMapper, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean
    public CalibrationReportRepository calibrationReportRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new CalibrationReportRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegressionReportRepository regressionReportRepository(JdbcTemplate jdbcTemplate) {
        return new RegressionReportRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegressionAlertRepository regressionAlertRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new RegressionAlertRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationPersistence evaluationPersistence(RubricsEvaluationMapper evaluations,
            RubricsCriterionResultMapper criteria, RubricsJudgeRolloutMapper rollouts,
            RunItemClaimRepository claims, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        return new EvaluationPersistence(
                evaluations,
                criteria,
                rollouts,
                claims,
                mapper,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean(RubricsEvaluationService.class)
    public DefaultRubricsEvaluationService rubricsEvaluationService(TemplateRegistry templates,
            EvaluationSubjectCatalog subjects, EvidencePackFactory evidence,
            RuleCriterionVerifier rules, RepeatedLlmCriterionVerifier llm,
            EvaluationSummaryCalculator summaries, EvaluationPersistence persistence,
            RubricsRunMapper runs, RubricsRunItemMapper items, RunItemClaimRepository claims,
            EvaluationDatasetRepository datasets, ValidationReportRepository validationReports,
            CalibrationReportRepository calibrationReports,
            RegressionReportRepository regressionReports,
            RegressionAlertRepository regressionAlerts,
            RubricsProperties properties,
            PlatformTransactionManager transactionManager,
            RubricsMetrics metrics) {
        return new DefaultRubricsEvaluationService(
                templates, subjects, evidence,
                rules, llm, summaries, persistence,
                runs, items, claims, datasets, validationReports, calibrationReports, regressionReports,
                regressionAlerts,
                Clock.systemUTC(), properties.getClaimLease(),
                new TransactionTemplate(transactionManager), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RunAdmissionService runAdmissionService(
            DefaultRubricsEvaluationService evaluations) {
        return new RunAdmissionService(evaluations);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "rubricsEvaluationExecutor")
    public ThreadPoolExecutor rubricsEvaluationExecutor(
            RubricsProperties properties, RubricsMetrics metrics) {
        properties.validate();
        int concurrency = properties.getRunnerConcurrency();
        int queueCapacity = properties.getQueueCapacity();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
        metrics.bindQueue(executor);
        return executor;
    }

    @Bean @ConditionalOnMissingBean
    public AutomationAdmissionPolicy automationAdmissionPolicy(
            ValidationReportRepository reports, RepeatedLlmCriterionVerifier llm) {
        return new AutomationAdmissionPolicy(reports, llm);
    }

    @Bean @ConditionalOnMissingBean
    public TaskCompletedEvaluationListener taskCompletedEvaluationListener(
            RubricsEvaluationService service, RunAdmissionService admissions,
            TemplateRegistry templates, TaskOutcomeQuery outcomes, RubricsProperties properties,
            AutomationAdmissionPolicy policy, ThreadPoolExecutor rubricsEvaluationExecutor,
            RubricsMetrics rubricsMetrics) {
        return new TaskCompletedEvaluationListener(
                service, admissions, templates, outcomes, properties, policy,
                rubricsEvaluationExecutor, rubricsMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RubricsRecoveryScheduler rubricsRecoveryScheduler(
            RubricsEvaluationService service,
            RubricsRunMapper runs,
            RubricsProperties properties,
            ThreadPoolExecutor rubricsEvaluationExecutor,
            RubricsMetrics rubricsMetrics) {
        return new RubricsRecoveryScheduler(
                service, runs, properties, rubricsEvaluationExecutor, rubricsMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RubricsScheduledDatasetRunner rubricsScheduledDatasetRunner(
            RubricsEvaluationService service,
            RunAdmissionService admissions,
            TemplateRegistry templates,
            RubricsProperties properties,
            AutomationAdmissionPolicy policy,
            ThreadPoolExecutor rubricsEvaluationExecutor,
            RubricsMetrics rubricsMetrics) {
        return new RubricsScheduledDatasetRunner(
                service,
                admissions,
                templates,
                properties,
                policy,
                rubricsEvaluationExecutor,
                Clock.systemUTC(),
                rubricsMetrics);
    }
}
