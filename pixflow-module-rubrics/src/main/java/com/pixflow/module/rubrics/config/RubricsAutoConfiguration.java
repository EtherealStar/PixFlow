package com.pixflow.module.rubrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.module.rubrics.api.RubricsController;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.automation.AutomationAdmissionPolicy;
import com.pixflow.module.rubrics.automation.TaskCompletedEvaluationListener;
import com.pixflow.module.rubrics.evidence.ImageEvidencePackBuilder;
import com.pixflow.module.rubrics.judge.MajorityVerdictReducer;
import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.persistence.RubricsCriterionResultMapper;
import com.pixflow.module.rubrics.persistence.RubricsEvaluationMapper;
import com.pixflow.module.rubrics.persistence.RubricsJudgeRolloutMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunItemMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.run.EvaluationPersistence;
import com.pixflow.module.rubrics.run.EvaluationRunCoordinator;
import com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver;
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
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@AutoConfiguration
@EnableConfigurationProperties(RubricsProperties.class)
@MapperScan(basePackageClasses = RubricsRunMapper.class, annotationClass = Mapper.class)
public class RubricsAutoConfiguration {
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
    public ImageEvidencePackBuilder imageEvidencePackBuilder(
            ObjectStorage storage, PublishedAssetReader publishedAssets, ImageCodec codec,
            ObjectMapper objectMapper) {
        return new ImageEvidencePackBuilder(storage, publishedAssets, codec, objectMapper);
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
            ObjectStorage storage, PublishedAssetReader publishedAssets,
            MajorityVerdictReducer reducer) {
        return new RepeatedLlmCriterionVerifier(chat, vision, router, mapper, entry -> {
            var location = publishedAssets.require(entry.sourceRef()).location();
            return new ChatMessage.UrlImageContent(java.net.URI.create(
                    storage.presignGet(location, Duration.ofMinutes(10)).toString()));
        }, reducer);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationPersistence evaluationPersistence(RubricsEvaluationMapper evaluations,
            RubricsCriterionResultMapper criteria, RubricsJudgeRolloutMapper rollouts,
            RubricsRunItemMapper items, ObjectMapper mapper) {
        return new EvaluationPersistence(evaluations, criteria, rollouts, items, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(RubricsEvaluationService.class)
    public RubricsEvaluationService rubricsEvaluationService(TemplateRegistry templates,
            ImageSubjectSnapshotResolver subjects, ImageEvidencePackBuilder evidence,
            RuleCriterionVerifier rules, RepeatedLlmCriterionVerifier llm,
            EvaluationSummaryCalculator summaries, EvaluationPersistence persistence,
            RubricsRunMapper runs, RubricsRunItemMapper items, RubricsEvaluationMapper evaluations,
            RubricsCriterionResultMapper criteria, RubricsJudgeRolloutMapper rollouts, ObjectMapper mapper) {
        return new EvaluationRunCoordinator(templates, subjects, evidence, rules, llm, summaries,
                persistence, runs, items, evaluations, criteria, rollouts, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RubricsController rubricsController(RubricsEvaluationService service, TemplateRegistry templates) {
        return new RubricsController(service, templates);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "rubricsEvaluationExecutor")
    public ThreadPoolExecutor rubricsEvaluationExecutor(RubricsProperties properties) {
        int concurrency = Math.max(1, properties.getRunnerConcurrency());
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean @ConditionalOnMissingBean
    public AutomationAdmissionPolicy automationAdmissionPolicy() {
        return new AutomationAdmissionPolicy();
    }

    @Bean @ConditionalOnMissingBean
    public TaskCompletedEvaluationListener taskCompletedEvaluationListener(RubricsEvaluationService service,
            TemplateRegistry templates, TaskOutcomeQuery outcomes, RubricsProperties properties,
            AutomationAdmissionPolicy policy, ThreadPoolExecutor rubricsEvaluationExecutor) {
        return new TaskCompletedEvaluationListener(service, templates, outcomes, properties, policy,
                rubricsEvaluationExecutor);
    }
}
