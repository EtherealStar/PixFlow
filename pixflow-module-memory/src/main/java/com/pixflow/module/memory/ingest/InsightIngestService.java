package com.pixflow.module.memory.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.insight.AnalysisInsight;
import com.pixflow.module.memory.insight.AnalysisInsightStatus;
import com.pixflow.module.memory.insight.ExtractedInsight;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.insight.InsightExtractor;
import com.pixflow.module.memory.insight.InsightLifecycleService;
import com.pixflow.module.memory.insight.InsightVectorRepo;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

public class InsightIngestService implements MemoryIngestService {
    private final InsightExtractor extractor;
    private final InsightDocMapper mapper;
    private final EmbeddingClient embeddingClient;
    private final InsightVectorRepo vectorRepo;
    private final InsightLifecycleService lifecycleService;
    private final MemoryProperties properties;
    private final Executor executor;
    private final Clock clock;

    public InsightIngestService(
            InsightExtractor extractor,
            InsightDocMapper mapper,
            EmbeddingClient embeddingClient,
            InsightVectorRepo vectorRepo,
            InsightLifecycleService lifecycleService,
            MemoryProperties properties,
            Executor executor,
            Clock clock) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.vectorRepo = Objects.requireNonNull(vectorRepo, "vectorRepo");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void ingestAsync(MemoryIngestRequest request) {
        executor.execute(() -> ingest(request));
    }

    void ingest(MemoryIngestRequest request) {
        List<InsightExtractor.MemoryItemSnapshot> neighbors = nearestContext(request);
        List<ExtractedInsight> extracted = extractor.extract(request, neighbors);
        for (ExtractedInsight insight : extracted) {
            if (!insight.valid()) {
                continue;
            }
            upsertInsight(insight);
        }
    }

    private void upsertInsight(ExtractedInsight extracted) {
        String normalized = normalize(extracted.text());
        String hash = md5(normalized);
        AnalysisInsight existing = mapper.selectOne(new LambdaQueryWrapper<AnalysisInsight>()
                .eq(AnalysisInsight::getContentHash, hash)
                .last("LIMIT 1"));
        if (existing != null) {
            lifecycleService.reinforce(new MemoryReinforcementEvent(
                    String.valueOf(existing.getId()),
                    "duplicate_ingest",
                    0.05,
                    0.05,
                    clock.instant(),
                    java.util.Map.of("content_hash", hash)));
            return;
        }

        for (String conflictId : extracted.conflictsWith()) {
            lifecycleService.suppress(conflictId, "conflict_ingest");
        }

        Instant now = clock.instant();
        AnalysisInsight entity = new AnalysisInsight();
        entity.setText(extracted.text());
        entity.setCategory(extracted.category());
        entity.setSource(extracted.source());
        entity.setConfidence(extracted.confidence());
        entity.setRelatedSku(extracted.relatedSku());
        entity.setContentHash(hash);
        entity.setImportance(extracted.importance());
        entity.setStatus(AnalysisInsightStatus.ACTIVE);
        entity.setAccessCount(0);
        entity.setDecayScore(1.0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setExpiresAt(extracted.expiresAt());
        mapper.insert(entity);

        float[] vector = embeddingClient.embed(List.of(entity.getText())).vectors().get(0).values();
        vectorRepo.ensureCollection(vector.length);
        vectorRepo.upsertActive(entity, vector);
    }

    private List<InsightExtractor.MemoryItemSnapshot> nearestContext(MemoryIngestRequest request) {
        if (request.recalledMemory() == null) {
            return List.of();
        }
        return request.recalledMemory().sections().stream()
                .flatMap(section -> section.items().stream())
                .filter(item -> item.type() == com.pixflow.module.memory.recall.MemoryType.INSIGHT)
                .limit(properties.getInsight().getDedupNeighbors())
                .map(item -> new InsightExtractor.MemoryItemSnapshot(item.id(), item.text(), item.category(), item.relatedSku()))
                .toList();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 digest is unavailable", ex);
        }
    }
}
