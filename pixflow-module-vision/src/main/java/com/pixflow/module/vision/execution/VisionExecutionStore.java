package com.pixflow.module.vision.execution;

import java.time.Instant;
import java.util.List;
import com.pixflow.module.vision.api.VisualAsset;

public interface VisionExecutionStore {
    VisionWorkItem get(long itemId);

    VisionWorkItem claim(long itemId, Instant now);

    void reserveProviderAttempt(long itemId, long generation, long epoch, Instant now);

    void recordStructureRound(long itemId, long generation, long epoch, int round, Instant now);

    boolean heartbeat(long itemId, long generation, long epoch, Instant now);

    boolean commitFacts(VisionWorkItem item, String factsJson, String metadataJson, Instant now);

    VisionWorkItem ensureImageWork(VisualAsset asset, Instant now);

    String currentImageFacts(long packageId, String skuId, long imageId, String fingerprint);

    void fail(VisionWorkItem item, String failureCode, Instant now);

    List<Long> expireStale(Instant before, Instant now, int limit);

    List<Long> pendingBefore(Instant before, int limit);
}
