package com.pixflow.module.vision.execution;

public record VisionWorkItem(
        long id,
        long packageId,
        String skuId,
        String scope,
        long targetImageId,
        String inputFingerprint,
        String status,
        long analysisGeneration,
        long runEpoch,
        long factStartVersion,
        int providerAttemptCount,
        int structureRoundCount) {
}
