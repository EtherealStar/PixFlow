package com.pixflow.module.memory.config;

import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.vector.VectorSearch;
import com.pixflow.module.memory.DefaultMemoryService;
import com.pixflow.module.memory.MemoryService;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.insight.DefaultInsightVectorSearch;
import com.pixflow.module.memory.insight.HybridInsightRecallService;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.insight.InsightKeywordSearch;
import com.pixflow.module.memory.insight.InsightRecallService;
import com.pixflow.module.memory.insight.InsightVectorSearch;
import com.pixflow.module.memory.insight.MybatisInsightKeywordSearch;
import com.pixflow.module.memory.insight.NoopInsightKeywordSearch;
import com.pixflow.module.memory.insight.VectorRecallReadiness;
import com.pixflow.module.memory.preference.MybatisPreferenceService;
import com.pixflow.module.memory.preference.NoopPreferenceService;
import com.pixflow.module.memory.preference.PreferenceService;
import com.pixflow.module.memory.preference.UserPreferenceMapper;
import com.pixflow.module.memory.recall.MemoryRanker;
import com.pixflow.module.memory.recall.RecallPlanner;
import com.pixflow.module.memory.recall.RecallSignalExtractor;
import com.pixflow.module.memory.recall.RrfFuser;
import com.pixflow.module.memory.skuhistory.MybatisSkuHistoryService;
import com.pixflow.module.memory.skuhistory.NoopSkuHistoryService;
import com.pixflow.module.memory.skuhistory.SkuHistoryMapper;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
@MapperScan(
        basePackageClasses = {UserPreferenceMapper.class, SkuHistoryMapper.class, InsightDocMapper.class},
        annotationClass = Mapper.class)
public class MemoryAutoConfiguration {

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
    public MemoryRanker memoryRanker(MemoryProperties properties, Clock clock) {
        return new MemoryRanker(properties, clock);
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
    @ConditionalOnBean(VectorSearch.class)
    public InsightVectorSearch insightVectorSearch(VectorSearch vectorSearch, MemoryProperties properties) {
        return new DefaultInsightVectorSearch(vectorSearch, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorRecallReadiness vectorRecallReadiness(
            ObjectProvider<InsightVectorSearch> vectorSearch,
            MemoryProperties properties) {
        // 启动探测只改变召回门状态；Qdrant 配置或网络故障不能阻断整个应用上下文。
        return VectorRecallReadiness.probe(vectorSearch.getIfAvailable(), properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightRecallService hybridInsightRecallService(
            ObjectProvider<EmbeddingClient> embeddingClient,
            ObjectProvider<InsightVectorSearch> vectorSearch,
            VectorRecallReadiness vectorReadiness,
            InsightKeywordSearch keywordSearch,
            RrfFuser rrfFuser,
            MemoryRanker memoryRanker,
            MemoryProperties properties) {
        return new HybridInsightRecallService(
                embeddingClient.getIfAvailable(),
                vectorSearch.getIfAvailable(),
                vectorReadiness,
                keywordSearch,
                rrfFuser,
                memoryRanker,
                properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryContextBuilder memoryContextBuilder(
            RecallSignalExtractor signalExtractor,
            RecallPlanner planner,
            PreferenceService preferenceService,
            SkuHistoryService skuHistoryService,
            InsightRecallService insightRecallService) {
        return new MemoryContextBuilder(
                signalExtractor,
                planner,
                preferenceService,
                skuHistoryService,
                insightRecallService);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryService memoryService(MemoryContextBuilder contextBuilder) {
        return new DefaultMemoryService(contextBuilder);
    }
}
