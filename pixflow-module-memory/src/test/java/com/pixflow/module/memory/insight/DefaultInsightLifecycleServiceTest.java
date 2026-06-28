package com.pixflow.module.memory.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pixflow.module.memory.config.MemoryProperties;
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

class DefaultInsightLifecycleServiceTest {

    @Test
    void maintenanceExpiresOldLowConfidenceInsightAndDeletesVectorPoint() {
        InsightDocMapper mapper = mock(InsightDocMapper.class);
        RecordingVectorRepo vectorRepo = new RecordingVectorRepo();
        AnalysisInsight old = insight(10L);
        old.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        old.setLastReinforcedAt(Instant.parse("2025-01-01T00:00:00Z"));
        old.setConfidence(0.1);
        old.setImportance(0.1);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(old));

        DefaultInsightLifecycleService service = new DefaultInsightLifecycleService(
                mapper,
                vectorRepo,
                new MemoryProperties(),
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));

        service.maintain();

        ArgumentCaptor<AnalysisInsight> captor = ArgumentCaptor.forClass(AnalysisInsight.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AnalysisInsightStatus.EXPIRED);
        assertThat(vectorRepo.deletedIds).containsExactly("10");
    }

    @Test
    void reinforceUpdatesAccessCountConfidenceImportanceAndDecay() {
        InsightDocMapper mapper = mock(InsightDocMapper.class);
        AnalysisInsight insight = insight(20L);
        insight.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        insight.setConfidence(0.5);
        insight.setImportance(0.4);
        insight.setAccessCount(2);
        when(mapper.selectById(eq("20"))).thenReturn(insight);

        DefaultInsightLifecycleService service = new DefaultInsightLifecycleService(
                mapper,
                new RecordingVectorRepo(),
                new MemoryProperties(),
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));

        service.reinforce(new MemoryReinforcementEvent(
                "20",
                "used_in_prompt",
                0.2,
                0.3,
                Instant.parse("2026-06-28T00:00:00Z"),
                Map.of()));

        ArgumentCaptor<AnalysisInsight> captor = ArgumentCaptor.forClass(AnalysisInsight.class);
        verify(mapper).updateById(captor.capture());
        AnalysisInsight updated = captor.getValue();
        assertThat(updated.getAccessCount()).isEqualTo(3);
        assertThat(updated.getConfidence()).isEqualTo(0.7);
        assertThat(updated.getImportance()).isEqualTo(0.7);
        assertThat(updated.getDecayScore()).isGreaterThan(0.7);
        assertThat(updated.getLastReinforcedAt()).isEqualTo(Instant.parse("2026-06-28T00:00:00Z"));
    }

    private static AnalysisInsight insight(Long id) {
        AnalysisInsight insight = new AnalysisInsight();
        insight.setId(id);
        insight.setText("夏季连衣裙白底主图点击率高于场景图");
        insight.setStatus(AnalysisInsightStatus.ACTIVE);
        insight.setDecayScore(1.0);
        return insight;
    }

    private static class RecordingVectorRepo implements InsightVectorRepo {
        private List<String> deletedIds = List.of();

        @Override
        public void ensureCollection(int dimension) {
        }

        @Override
        public void upsertActive(AnalysisInsight insight, float[] vector) {
        }

        @Override
        public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
            return List.of();
        }

        @Override
        public void delete(String insightId) {
            deletedIds = List.of(insightId);
        }
    }
}
