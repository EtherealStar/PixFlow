package com.pixflow.module.vision.domain;

import java.time.Instant;

/**
 * current facts 与 current work 的原子持久化边界。
 */
public interface VisionStateStore {
    VisionStateSnapshot get(long packageId, String skuId);

    StateMutation replaceByAdministrator(
            long packageId,
            String skuId,
            long expectedVersion,
            String factsJson,
            Instant now);

    StateMutation requestReanalysis(
            long packageId,
            String skuId,
            long expectedGeneration,
            String requestId,
            String inputFingerprint,
            boolean noImage,
            Instant now);
}
