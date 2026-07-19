package com.pixflow.module.vision.domain;

import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.api.VisualFactsWriter;
import java.time.Instant;

public record VisionStateSnapshot(
        long packageId,
        String skuId,
        String inputFingerprint,
        String factsJson,
        long factVersion,
        VisualFactsWriter writer,
        Instant factsUpdatedAt,
        AnalysisStatus analysisStatus,
        long analysisGeneration,
        long runEpoch,
        int providerAttemptCount,
        int structureRoundCount,
        String lastRequestId,
        String failureCode) {

    public boolean active() {
        return analysisStatus == AnalysisStatus.PENDING
                || analysisStatus == AnalysisStatus.RUNNING
                || analysisStatus == AnalysisStatus.EXPIRED;
    }
}
