package com.pixflow.module.memory.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class DefaultInsightLifecycleService implements InsightLifecycleService {
    private final InsightDocMapper mapper;
    private final InsightVectorRepo vectorRepo;
    private final MemoryProperties properties;
    private final Clock clock;

    public DefaultInsightLifecycleService(
            InsightDocMapper mapper,
            InsightVectorRepo vectorRepo,
            MemoryProperties properties,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.vectorRepo = Objects.requireNonNull(vectorRepo, "vectorRepo");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void maintain() {
        List<AnalysisInsight> active = mapper.selectList(new LambdaQueryWrapper<AnalysisInsight>()
                .eq(AnalysisInsight::getStatus, AnalysisInsightStatus.ACTIVE));
        Instant now = clock.instant();
        for (AnalysisInsight insight : active) {
            double decay = calculateDecay(insight, now);
            insight.setDecayScore(decay);
            insight.setUpdatedAt(now);
            if (isExpired(insight, now) || decay <= properties.getInsight().getLifecycle().getExpireThreshold()) {
                insight.setStatus(AnalysisInsightStatus.EXPIRED);
                mapper.updateById(insight);
                vectorRepo.delete(String.valueOf(insight.getId()));
            } else if (decay <= properties.getInsight().getLifecycle().getSuppressThreshold()) {
                insight.setStatus(AnalysisInsightStatus.SUPPRESSED);
                mapper.updateById(insight);
                vectorRepo.delete(String.valueOf(insight.getId()));
            } else {
                mapper.updateById(insight);
            }
        }
    }

    @Override
    public void suppress(String insightId, String reason) {
        transition(insightId, AnalysisInsightStatus.SUPPRESSED);
    }

    @Override
    public void expire(String insightId, String reason) {
        transition(insightId, AnalysisInsightStatus.EXPIRED);
    }

    @Override
    public void reinforce(MemoryReinforcementEvent event) {
        AnalysisInsight insight = mapper.selectById(event.insightId());
        if (insight == null || insight.getStatus() != AnalysisInsightStatus.ACTIVE) {
            return;
        }
        Instant occurredAt = event.occurredAt() == null ? clock.instant() : event.occurredAt();
        insight.setLastReinforcedAt(occurredAt);
        insight.setAccessCount((insight.getAccessCount() == null ? 0 : insight.getAccessCount()) + 1);
        insight.setConfidence(clamp(defaulted(insight.getConfidence(), 0.5) + event.confidenceDelta()));
        insight.setImportance(clamp(defaulted(insight.getImportance(), 0.5) + event.importanceDelta()));
        // 强化后立即重算衰减分，避免刚被采纳的结论仍因旧时间戳被压低。
        insight.setDecayScore(calculateDecay(insight, occurredAt));
        insight.setUpdatedAt(occurredAt);
        mapper.updateById(insight);
    }

    double calculateDecay(AnalysisInsight insight, Instant now) {
        Instant basis = insight.getLastReinforcedAt() != null ? insight.getLastReinforcedAt() : insight.getCreatedAt();
        if (basis == null) {
            basis = now;
        }
        long ageDays = Math.max(0, Duration.between(basis, now).toDays());
        double halfLife = properties.getInsight().getLifecycle().getDecayHalfLifeDays();
        double timeFactor = Math.pow(0.5d, ageDays / halfLife);
        double confidenceFactor = 0.5d + 0.5d * defaulted(insight.getConfidence(), 0.5);
        double importanceFactor = 0.5d + 0.5d * defaulted(insight.getImportance(), 0.5);
        return clamp(timeFactor * confidenceFactor * importanceFactor);
    }

    private void transition(String insightId, AnalysisInsightStatus status) {
        if (insightId == null || insightId.isBlank()) {
            return;
        }
        AnalysisInsight insight = mapper.selectById(insightId);
        if (insight == null || insight.getStatus() == status) {
            return;
        }
        insight.setStatus(status);
        insight.setUpdatedAt(clock.instant());
        mapper.updateById(insight);
        vectorRepo.delete(String.valueOf(insight.getId()));
    }

    private static boolean isExpired(AnalysisInsight insight, Instant now) {
        return insight.getExpiresAt() != null && !insight.getExpiresAt().isAfter(now);
    }

    private static double defaulted(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }
}
