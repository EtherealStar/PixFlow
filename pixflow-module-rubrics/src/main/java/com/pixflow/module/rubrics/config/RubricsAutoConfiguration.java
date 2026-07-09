package com.pixflow.module.rubrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.memory.ingest.MemoryIngestService;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import com.pixflow.module.rubrics.baseline.BaselineService;
import com.pixflow.module.rubrics.baseline.RegressionAlertService;
import com.pixflow.module.rubrics.baseline.RegressionComparator;
import com.pixflow.module.rubrics.api.RubricsAdminController;
import com.pixflow.module.rubrics.api.RubricsReportController;
import com.pixflow.module.rubrics.feedback.MemoryFeedbackTrigger;
import com.pixflow.module.rubrics.feedback.ScoreFeedbackWriter;
import com.pixflow.module.rubrics.judge.JudgePromptBuilder;
import com.pixflow.module.rubrics.judge.LlmJudge;
import com.pixflow.module.rubrics.judge.VerdictParser;
import com.pixflow.module.rubrics.persistence.RubricsAlertMapper;
import com.pixflow.module.rubrics.persistence.RubricsBaselineMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import com.pixflow.module.rubrics.rule.CoverageCompletenessRuleVerifier;
import com.pixflow.module.rubrics.rule.FileSizeRuleVerifier;
import com.pixflow.module.rubrics.rule.FormatRuleVerifier;
import com.pixflow.module.rubrics.rule.HitlSmoothnessRuleVerifier;
import com.pixflow.module.rubrics.rule.ParamValidityRuleVerifier;
import com.pixflow.module.rubrics.rule.ResolutionRuleVerifier;
import com.pixflow.module.rubrics.rule.RuleVerifier;
import com.pixflow.module.rubrics.run.EvaluationRunner;
import com.pixflow.module.rubrics.run.ItemEvaluator;
import com.pixflow.module.rubrics.run.RubricsDailyBatchScheduler;
import com.pixflow.module.rubrics.run.RubricsTriggerListener;
import com.pixflow.module.rubrics.score.ScoreAggregator;
import com.pixflow.module.rubrics.template.TemplateLoader;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(RubricsProperties.class)
@MapperScan(value = "com.pixflow.module.rubrics.persistence", annotationClass = Mapper.class)
public class RubricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public TemplateLoader templateLoader() {
        return new TemplateLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateRegistry templateRegistry(TemplateLoader templateLoader, RubricsProperties properties) {
        return new TemplateRegistry(templateLoader.loadClasspath(properties.getTemplateScan().getClasspathPrefix()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ScoreAggregator scoreAggregator() {
        return new ScoreAggregator();
    }

    @Bean
    @ConditionalOnMissingBean
    public VerdictParser verdictParser(ObjectMapper objectMapper) {
        return new VerdictParser(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JudgePromptBuilder judgePromptBuilder() {
        return new JudgePromptBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelClient.class)
    public LlmJudge llmJudge(
            ChatModelClient chatModelClient,
            ObjectProvider<VisionModelClient> visionModelClient,
            JudgePromptBuilder promptBuilder,
            VerdictParser verdictParser,
            RubricsProperties properties) {
        return new LlmJudge(chatModelClient, visionModelClient.getIfAvailable(), promptBuilder, verdictParser, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ImageCodec.class)
    public ResolutionRuleVerifier resolutionRuleVerifier(ImageCodec imageCodec) {
        return new ResolutionRuleVerifier(imageCodec);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ImageCodec.class)
    public FormatRuleVerifier formatRuleVerifier(ImageCodec imageCodec) {
        return new FormatRuleVerifier(imageCodec);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileSizeRuleVerifier fileSizeRuleVerifier() {
        return new FileSizeRuleVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public HitlSmoothnessRuleVerifier hitlSmoothnessRuleVerifier() {
        return new HitlSmoothnessRuleVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public CoverageCompletenessRuleVerifier coverageCompletenessRuleVerifier() {
        return new CoverageCompletenessRuleVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public ParamValidityRuleVerifier paramValidityRuleVerifier() {
        return new ParamValidityRuleVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ObjectStorage.class)
    public ItemEvaluator itemEvaluator(
            ObjectStorage objectStorage,
            List<RuleVerifier> ruleVerifiers,
            ObjectProvider<LlmJudge> llmJudge,
            ScoreAggregator scoreAggregator,
            ObjectMapper objectMapper) {
        return new ItemEvaluator(objectStorage, ruleVerifiers, llmJudge.getIfAvailable(), scoreAggregator, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({RubricsScoreMapper.class, SkuHistoryService.class})
    public ScoreFeedbackWriter scoreFeedbackWriter(RubricsScoreMapper scoreMapper, SkuHistoryService skuHistoryService) {
        return new ScoreFeedbackWriter(scoreMapper, skuHistoryService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({RubricsScoreMapper.class, MemoryIngestService.class})
    public MemoryFeedbackTrigger memoryFeedbackTrigger(RubricsScoreMapper scoreMapper, MemoryIngestService ingestService) {
        return new MemoryFeedbackTrigger(scoreMapper, ingestService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({RubricsBaselineMapper.class, RubricsRunMapper.class})
    public BaselineService baselineService(RubricsBaselineMapper baselineMapper, RubricsRunMapper runMapper,
                                           Clock clock) {
        return new BaselineService(baselineMapper, runMapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RubricsScoreMapper.class)
    public RegressionComparator regressionComparator(
            RubricsScoreMapper scoreMapper,
            ObjectMapper objectMapper,
            RubricsProperties properties) {
        return new RegressionComparator(scoreMapper, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RubricsAlertMapper.class)
    public RegressionAlertService regressionAlertService(
            RubricsAlertMapper alertMapper,
            ObjectMapper objectMapper,
            RubricsProperties properties,
            Clock clock) {
        return new RegressionAlertService(alertMapper, objectMapper, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rubricsExecutor")
    public Executor rubricsExecutor(RubricsProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pixflow-rubrics-");
        executor.setCorePoolSize(Math.max(1, properties.getEventTrigger().getWorkerThreads()));
        executor.setMaxPoolSize(Math.max(1, properties.getEventTrigger().getWorkerThreads()));
        executor.setQueueCapacity(Math.max(1, properties.getEventTrigger().getQueueSize()));
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            ProcessResultMapper.class,
            ProcessTaskMapper.class,
            RubricsRunMapper.class,
            RubricsRunItemMapper.class,
            ItemEvaluator.class,
            ScoreFeedbackWriter.class,
            MemoryFeedbackTrigger.class
    })
    public EvaluationRunner evaluationRunner(
            TemplateRegistry templateRegistry,
            ProcessResultMapper resultMapper,
            ProcessTaskMapper taskMapper,
            RubricsRunMapper runMapper,
            RubricsRunItemMapper runItemMapper,
            ItemEvaluator itemEvaluator,
            ScoreFeedbackWriter scoreFeedbackWriter,
            MemoryFeedbackTrigger memoryFeedbackTrigger,
            Clock clock) {
        return new EvaluationRunner(
                templateRegistry,
                resultMapper,
                taskMapper,
                runMapper,
                runItemMapper,
                itemEvaluator,
                scoreFeedbackWriter,
                memoryFeedbackTrigger,
                clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EvaluationRunner.class)
    public RubricsTriggerListener rubricsTriggerListener(
            EvaluationRunner runner,
            RubricsProperties properties,
            @Qualifier("rubricsExecutor") Executor executor) {
        return new RubricsTriggerListener(runner, properties, executor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EvaluationRunner.class)
    public RubricsDailyBatchScheduler rubricsDailyBatchScheduler(EvaluationRunner runner, RubricsProperties properties) {
        return new RubricsDailyBatchScheduler(runner, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EvaluationRunner.class, BaselineService.class, RubricsAlertMapper.class})
    public RubricsAdminController rubricsAdminController(
            TemplateRegistry templateRegistry,
            EvaluationRunner runner,
            BaselineService baselineService,
            RubricsAlertMapper alertMapper,
            Clock clock) {
        return new RubricsAdminController(templateRegistry, runner, baselineService, alertMapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({RubricsScoreMapper.class, RegressionComparator.class, RegressionAlertService.class})
    public RubricsReportController rubricsReportController(
            RubricsScoreMapper scoreMapper,
            RegressionComparator regressionComparator,
            RegressionAlertService alertService) {
        return new RubricsReportController(scoreMapper, regressionComparator, alertService);
    }
}
