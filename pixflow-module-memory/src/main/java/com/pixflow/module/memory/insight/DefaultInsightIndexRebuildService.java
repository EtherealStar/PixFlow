package com.pixflow.module.memory.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import java.util.List;
import java.util.Objects;

public class DefaultInsightIndexRebuildService implements InsightIndexRebuildService {
    private final InsightDocMapper mapper;
    private final EmbeddingClient embeddingClient;
    private final InsightVectorRepo vectorRepo;

    public DefaultInsightIndexRebuildService(
            InsightDocMapper mapper,
            EmbeddingClient embeddingClient,
            InsightVectorRepo vectorRepo) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.vectorRepo = Objects.requireNonNull(vectorRepo, "vectorRepo");
    }

    @Override
    public RebuildResult rebuildActiveIndex() {
        List<AnalysisInsight> active = mapper.selectList(new LambdaQueryWrapper<AnalysisInsight>()
                .eq(AnalysisInsight::getStatus, AnalysisInsightStatus.ACTIVE)
                .and(wrapper -> wrapper.isNull(AnalysisInsight::getExpiresAt)
                        .or()
                        .gtSql(AnalysisInsight::getExpiresAt, "NOW()")));
        int upserted = 0;
        for (AnalysisInsight insight : active) {
            float[] vector = embeddingClient.embed(List.of(insight.getText())).vectors().get(0).values();
            vectorRepo.ensureCollection(vector.length);
            vectorRepo.upsertActive(insight, vector);
            upserted++;
        }
        // VectorStore 当前没有列出 collection 全量 point id 的接口；本入口先保证 active 行可重建。
        return new RebuildResult(active.size(), upserted);
    }
}
