package com.pixflow.module.memory.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pixflow.infra.ai.embedding.EmbeddingResult;
import com.pixflow.infra.ai.embedding.EmbeddingVector;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.insight.AnalysisInsight;
import com.pixflow.module.memory.insight.AnalysisInsightStatus;
import com.pixflow.module.memory.insight.ExtractedInsight;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.insight.InsightExtractor;
import com.pixflow.module.memory.insight.InsightLifecycleService;
import com.pixflow.module.memory.insight.InsightVectorRepo;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InsightIngestServiceTest {

    @Test
    void insertsNewInsightAndUpsertsVectorPoint() {
        InsightDocMapper mapper = mock(InsightDocMapper.class);
        RecordingVectorRepo vectorRepo = new RecordingVectorRepo();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.insert(any(AnalysisInsight.class))).thenAnswer(invocation -> {
            AnalysisInsight insight = invocation.getArgument(0);
            insight.setId(101L);
            return 1;
        });

        InsightIngestService service = service(mapper, vectorRepo, lifecycle, List.of(new ExtractedInsight(
                "夏季连衣裙白底主图点击率高于场景图",
                "连衣裙",
                "test",
                0.9,
                "SKU123",
                0.8,
                null,
                List.of())));

        service.ingestAsync(request());

        ArgumentCaptor<AnalysisInsight> captor = ArgumentCaptor.forClass(AnalysisInsight.class);
        verify(mapper).insert(captor.capture());
        AnalysisInsight inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(AnalysisInsightStatus.ACTIVE);
        assertThat(inserted.getContentHash()).hasSize(32);
        assertThat(vectorRepo.upsertedId).isEqualTo(101L);
        assertThat(vectorRepo.vector).containsExactly(1.0f, 2.0f);
    }

    @Test
    void duplicateHashReinforcesExistingInsightInsteadOfInserting() {
        InsightDocMapper mapper = mock(InsightDocMapper.class);
        AnalysisInsight existing = new AnalysisInsight();
        existing.setId(55L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        InsightIngestService service = service(mapper, new RecordingVectorRepo(), lifecycle, List.of(new ExtractedInsight(
                "夏季连衣裙白底主图点击率高于场景图",
                "连衣裙",
                "test",
                0.9,
                "SKU123",
                0.8,
                null,
                List.of())));

        service.ingestAsync(request());

        verify(mapper, never()).insert(any(AnalysisInsight.class));
        assertThat(lifecycle.reinforcedId).isEqualTo("55");
    }

    private static InsightIngestService service(
            InsightDocMapper mapper,
            InsightVectorRepo vectorRepo,
            InsightLifecycleService lifecycle,
            List<ExtractedInsight> extracted) {
        return new InsightIngestService(
                (request, neighbors) -> extracted,
                mapper,
                texts -> new EmbeddingResult(List.of(new EmbeddingVector(0, new float[] {1.0f, 2.0f})), new TokenUsage(0, 0, 0)),
                vectorRepo,
                lifecycle,
                new MemoryProperties(),
                Runnable::run,
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
    }

    private static MemoryIngestRequest request() {
        return new MemoryIngestRequest(
                "c1",
                1,
                "trace",
                "处理 SKU123 连衣裙主图",
                "建议使用白底主图",
                null,
                List.of("ctr improved"),
                List.of("SKU123"),
                List.of("连衣裙"),
                Map.of());
    }

    private static class RecordingVectorRepo implements InsightVectorRepo {
        private Long upsertedId;
        private float[] vector;

        @Override
        public void ensureCollection(int dimension) {
        }

        @Override
        public void upsertActive(AnalysisInsight insight, float[] vector) {
            this.upsertedId = insight.getId();
            this.vector = vector;
        }

        @Override
        public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
            return List.of();
        }

        @Override
        public void delete(String insightId) {
        }
    }

    private static class RecordingLifecycle implements InsightLifecycleService {
        private String reinforcedId;

        @Override
        public void maintain() {
        }

        @Override
        public void suppress(String insightId, String reason) {
        }

        @Override
        public void expire(String insightId, String reason) {
        }

        @Override
        public void reinforce(MemoryReinforcementEvent event) {
            reinforcedId = event.insightId();
        }
    }
}
