package com.pixflow.module.vision.persistence;

import com.pixflow.module.vision.execution.ProviderAttemptBudgetExceededException;
import com.pixflow.module.vision.execution.VisionExecutionStore;
import com.pixflow.module.vision.execution.VisionWorkItem;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.pixflow.module.vision.api.VisualAsset;
import org.springframework.transaction.annotation.Transactional;

public final class MybatisVisionExecutionStore implements VisionExecutionStore {
    private final VisionStateMapper mapper;

    public MybatisVisionExecutionStore(VisionStateMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public VisionWorkItem get(long itemId) {
        return mapper.findWorkItem(itemId);
    }

    @Override
    @Transactional
    public VisionWorkItem claim(long itemId, Instant now) {
        VisionWorkItem current = mapper.lockWorkItem(itemId);
        if (current == null || !("PENDING".equals(current.status()) || "EXPIRED".equals(current.status()))) {
            return null;
        }
        if (mapper.claimWorkItem(itemId, current.analysisGeneration(), now) != 1) {
            return null;
        }
        return mapper.findWorkItem(itemId);
    }

    @Override
    public void reserveProviderAttempt(long itemId, long generation, long epoch, Instant now) {
        if (mapper.reserveProviderAttempt(itemId, generation, epoch, now) != 1) {
            throw new ProviderAttemptBudgetExceededException("provider attempt budget exhausted or owner stale");
        }
    }

    @Override
    public void recordStructureRound(long itemId, long generation, long epoch, int round, Instant now) {
        if (mapper.recordStructureRound(itemId, generation, epoch, round, now) != 1) {
            throw new IllegalStateException("visual analysis owner is stale");
        }
    }

    @Override
    public boolean heartbeat(long itemId, long generation, long epoch, Instant now) {
        return mapper.heartbeat(itemId, generation, epoch, now) == 1;
    }

    @Override
    @Transactional
    public boolean commitFacts(VisionWorkItem item, String factsJson, String metadataJson, Instant now) {
        int factsChanged = "IMAGE".equals(item.scope())
                ? writeImageFacts(item, factsJson, metadataJson, now)
                : writeSkuFacts(item, factsJson, metadataJson, now);
        if (factsChanged != 1) {
            return false;
        }
        if (mapper.completeWorkItem(item.id(), item.analysisGeneration(), item.runEpoch(), now) != 1) {
            throw new IllegalStateException("visual work item changed during commit");
        }
        mapper.refreshJob(item.packageId(), now);
        return true;
    }

    @Override
    @Transactional
    public VisionWorkItem ensureImageWork(VisualAsset asset, Instant now) {
        mapper.ensureImageItem(asset.packageId(), asset.skuId(), asset.imageId(), asset.contentHash(), now);
        VisionWorkItem item = mapper.lockImageWorkItem(asset.packageId(), asset.skuId(), asset.imageId());
        if (item == null) {
            throw new IllegalStateException("focused image work item was not persisted");
        }
        if (item.inputFingerprint().equals(asset.contentHash())) {
            return item;
        }
        mapper.invalidateImageFacts(
                asset.packageId(), asset.skuId(), asset.imageId(), asset.contentHash(), now);
        ImageFactsRow facts = mapper.findImageFacts(asset.packageId(), asset.skuId(), asset.imageId());
        long version = facts == null ? 0 : facts.version();
        mapper.resetForInput(item.id(), asset.contentHash(), version, "PENDING", null, now);
        return mapper.findWorkItem(item.id());
    }

    @Override
    public String currentImageFacts(long packageId, String skuId, long imageId, String fingerprint) {
        ImageFactsRow row = mapper.findImageFacts(packageId, skuId, imageId);
        return row != null && fingerprint.equals(row.inputFingerprint()) ? row.factsJson() : null;
    }

    @Override
    @Transactional
    public void fail(VisionWorkItem item, String failureCode, Instant now) {
        if (mapper.failWorkItem(item.id(), item.analysisGeneration(), item.runEpoch(), failureCode, now) == 1) {
            mapper.refreshJob(item.packageId(), now);
        }
    }

    @Override
    public List<Long> expireStale(Instant before, Instant now, int limit) {
        List<Long> ids = mapper.findStaleRunning(before, limit);
        return ids.stream().filter(id -> mapper.expireRunning(id, before, now) == 1).toList();
    }

    @Override
    public List<Long> pendingBefore(Instant before, int limit) {
        return mapper.findPendingBefore(before, limit);
    }

    private int writeSkuFacts(VisionWorkItem item, String factsJson, String metadataJson, Instant now) {
        return item.factStartVersion() == 0
                ? mapper.insertAiSkuFacts(item.packageId(), item.skuId(), item.inputFingerprint(),
                        factsJson, metadataJson, now)
                : mapper.updateAiSkuFacts(item.packageId(), item.skuId(), item.inputFingerprint(),
                        item.factStartVersion(), factsJson, metadataJson, now);
    }

    private int writeImageFacts(VisionWorkItem item, String factsJson, String metadataJson, Instant now) {
        return item.factStartVersion() == 0
                ? mapper.insertAiImageFacts(item.packageId(), item.skuId(), item.targetImageId(),
                        item.inputFingerprint(), factsJson, metadataJson, now)
                : mapper.updateAiImageFacts(item.packageId(), item.skuId(), item.targetImageId(),
                        item.inputFingerprint(), item.factStartVersion(), factsJson, metadataJson, now);
    }
}
