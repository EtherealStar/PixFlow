package com.pixflow.module.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.vector.VectorStore;
import com.pixflow.module.memory.DefaultMemoryService;
import com.pixflow.module.memory.MemoryService;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.ingest.InsightIngestService;
import com.pixflow.module.memory.ingest.MemoryIngestService;
import com.pixflow.module.memory.ingest.NoopMemoryIngestService;
import com.pixflow.module.memory.insight.DefaultInsightIndexRebuildService;
import com.pixflow.module.memory.insight.DefaultInsightVectorRepo;
import com.pixflow.module.memory.insight.DefaultInsightLifecycleService;
import com.pixflow.module.memory.insight.InsightExtractor;
import com.pixflow.module.memory.insight.InsightIndexRebuildService;
import com.pixflow.module.memory.insight.InsightRecallService;
import com.pixflow.module.memory.insight.HybridInsightRecallService;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.insight.InsightKeywordSearch;
import com.pixflow.module.memory.insight.InsightLifecycleService;
import com.pixflow.module.memory.insight.InsightVectorRepo;
import com.pixflow.module.memory.insight.LlmInsightExtractor;
import com.pixflow.module.memory.insight.MybatisInsightKeywordSearch;
import com.pixflow.module.memory.insight.NoopInsightIndexRebuildService;
import com.pixflow.module.memory.insight.NoopInsightKeywordSearch;
import com.pixflow.module.memory.insight.NoopInsightRecallService;
import com.pixflow.module.memory.insight.NoopInsightLifecycleService;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementService;
import com.pixflow.module.memory.lifecycle.NoopMemoryReinforcementService;
import com.pixflow.module.memory.lifecycle.ScheduledInsightMaintenance;
import com.pixflow.module.memory.preference.NoopPreferenceService;
import com.pixflow.module.memory.preference.MybatisPreferenceService;
import com.pixflow.module.memory.preference.PreferenceService;
import com.pixflow.module.memory.preference.UserPreferenceMapper;
import com.pixflow.module.memory.recall.MemoryRanker;
import com.pixflow.module.memory.recall.RecallPlanner;
import com.pixflow.module.memory.recall.RecallSignalExtractor;
import com.pixflow.module.memory.recall.RrfFuser;
import com.pixflow.module.memory.skuhistory.MybatisSkuHistoryService;
import com.pixflow.module.memory.skuhistory.NoopSkuHistoryService;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import com.pixflow.module.memory.skuhistory.SkuHistoryMapper;
import java.time.Clock;
import java.util.concurrent.Executor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
@EnableScheduling
@MapperScan(
        basePackageClasses = {UserPreferenceMapper.class, SkuHistoryMapper.class, InsightDocMapper.class},
        annotationClass = Mapper.class)
public class MemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock memoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper memoryObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(name = "memoryIngestExecutor")
    public Executor memoryIngestExecutor(MemoryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pixflow-memory-ingest-");
        executor.setCorePoolSize(properties.getIngest().getPool().getCoreSize());
        executor.setMaxPoolSize(properties.getIngest().getPool().getMaxSize());
        executor.setQueueCapacity(properties.getIngest().getPool().getQueueCapacity());
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public RecallSignalExtractor recallSignalExtractor() {
        return new RecallSignalExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public RecallPlanner recallPlanner(MemoryProperties properties) {
        return new RecallPlanner(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RrfFuser rrfFuser() {
        return new RrfFuser();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryRanker memoryRanker(MemoryProperties properties, Clock memoryClock) {
        return new MemoryRanker(properties, memoryClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PreferenceService preferenceService(ObjectProvider<UserPreferenceMapper> mapper) {
        UserPreferenceMapper resolved = mapper.getIfAvailable();
        return resolved == null ? new NoopPreferenceService() : new MybatisPreferenceService(resolved);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkuHistoryService skuHistoryService(ObjectProvider<SkuHistoryMapper> mapper) {
        SkuHistoryMapper resolved = mapper.getIfAvailable();
        return resolved == null ? new NoopSkuHistoryService() : new MybatisSkuHistoryService(resolved);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightKeywordSearch insightKeywordSearch(ObjectProvider<InsightDocMapper> mapper) {
        InsightDocMapper resolved = mapper.getIfAvailable();
        return resolved == null ? new NoopInsightKeywordSearch() : new MybatisInsightKeywordSearch(resolved);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VectorStore.class)
    public InsightVectorRepo insightVectorRepo(VectorStore vectorStore, MemoryProperties properties) {
        return new DefaultInsightVectorRepo(vectorStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightRecallService hybridInsightRecallService(
            ObjectProvider<EmbeddingClient> embeddingClient,
            ObjectProvider<InsightVectorRepo> vectorRepo,
            InsightKeywordSearch keywordSearch,
            RrfFuser rrfFuser,
            MemoryRanker memoryRanker,
            MemoryProperties properties) {
        EmbeddingClient resolvedEmbedding = embeddingClient.getIfAvailable();
        InsightVectorRepo resolvedVectorRepo = vectorRepo.getIfAvailable();
        if (resolvedEmbedding == null || resolvedVectorRepo == null) {
            return new NoopInsightRecallService();
        }
        return new HybridInsightRecallService(resolvedEmbedding, resolvedVectorRepo, keywordSearch, rrfFuser, memoryRanker, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelClient.class)
    public InsightExtractor insightExtractor(ChatModelClient chatModelClient, ObjectMapper memoryObjectMapper) {
        return new LlmInsightExtractor(chatModelClient, memoryObjectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({InsightExtractor.class, InsightDocMapper.class, EmbeddingClient.class, InsightVectorRepo.class, InsightLifecycleService.class})
    public MemoryIngestService insightIngestService(
            InsightExtractor extractor,
            InsightDocMapper mapper,
            EmbeddingClient embeddingClient,
            InsightVectorRepo vectorRepo,
            InsightLifecycleService lifecycleService,
            MemoryProperties properties,
            @Qualifier("memoryIngestExecutor") Executor memoryIngestExecutor,
            Clock memoryClock) {
        return new InsightIngestService(
                extractor,
                mapper,
                embeddingClient,
                vectorRepo,
                lifecycleService,
                properties,
                memoryIngestExecutor,
                memoryClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryIngestService memoryIngestService() {
        return new NoopMemoryIngestService();
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightLifecycleService insightLifecycleService(
            ObjectProvider<InsightDocMapper> mapper,
            ObjectProvider<InsightVectorRepo> vectorRepo,
            MemoryProperties properties,
            Clock memoryClock) {
        InsightDocMapper resolvedMapper = mapper.getIfAvailable();
        InsightVectorRepo resolvedVectorRepo = vectorRepo.getIfAvailable();
        if (resolvedMapper == null || resolvedVectorRepo == null) {
            return new NoopInsightLifecycleService();
        }
        return new DefaultInsightLifecycleService(resolvedMapper, resolvedVectorRepo, properties, memoryClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledInsightMaintenance scheduledInsightMaintenance(InsightLifecycleService lifecycleService) {
        return new ScheduledInsightMaintenance(lifecycleService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({InsightDocMapper.class, EmbeddingClient.class, InsightVectorRepo.class})
    public InsightIndexRebuildService insightIndexRebuildService(
            InsightDocMapper mapper,
            EmbeddingClient embeddingClient,
            InsightVectorRepo vectorRepo) {
        return new DefaultInsightIndexRebuildService(mapper, embeddingClient, vectorRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightIndexRebuildService noopInsightIndexRebuildService() {
        return new NoopInsightIndexRebuildService();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryReinforcementService memoryReinforcementService() {
        return new NoopMemoryReinforcementService();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryContextBuilder memoryContextBuilder(
            RecallSignalExtractor signalExtractor,
            RecallPlanner planner,
            PreferenceService preferenceService,
            SkuHistoryService skuHistoryService,
            InsightRecallService insightRecallService) {
        return new MemoryContextBuilder(signalExtractor, planner, preferenceService, skuHistoryService, insightRecallService);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryService memoryService(
            MemoryContextBuilder contextBuilder,
            MemoryIngestService ingestService,
            MemoryReinforcementService reinforcementService) {
        return new DefaultMemoryService(contextBuilder, ingestService, reinforcementService);
    }
}
