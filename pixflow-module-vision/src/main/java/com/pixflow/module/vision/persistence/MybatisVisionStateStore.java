package com.pixflow.module.vision.persistence;

import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.domain.StateMutation;
import com.pixflow.module.vision.domain.InputReconciliation;
import com.pixflow.module.vision.domain.VisionInputStateStore;
import com.pixflow.module.vision.domain.VisionStateSnapshot;
import com.pixflow.module.vision.domain.VisionStateStore;
import com.pixflow.module.vision.domain.VisualInputFingerprint;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

public class MybatisVisionStateStore implements VisionStateStore, VisionInputStateStore {
    private final VisionStateMapper mapper;

    public MybatisVisionStateStore(VisionStateMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public VisionStateSnapshot get(long packageId, String skuId) {
        VisionStateRow row = mapper.findSkuState(packageId, skuId);
        return row == null ? empty(packageId, skuId) : snapshot(row);
    }

    @Override
    public Set<String> knownSkus(long packageId) {
        return Set.copyOf(mapper.findKnownSkus(packageId));
    }

    @Override
    @Transactional
    public InputReconciliation reconcileSkuInput(
            long packageId,
            String skuId,
            String fingerprint,
            boolean noImage,
            Instant now) {
        mapper.ensureSkuItem(packageId, skuId, fingerprint, now);
        VisionStateRow row = mapper.lockSkuState(packageId, skuId);
        boolean fingerprintChanged = !fingerprint.equals(row.getInputFingerprint());
        boolean neverAnalyzed = "NOT_ANALYZED".equals(row.getFailureCode());
        if (!fingerprintChanged && !neverAnalyzed) {
            return new InputReconciliation(row.getItemId(), false);
        }
        if (fingerprintChanged) {
            mapper.invalidateFactsForInput(packageId, skuId, fingerprint, now);
            row = mapper.lockSkuState(packageId, skuId);
        }
        mapper.resetForInput(
                row.getItemId(),
                fingerprint,
                row.getFactVersion(),
                noImage ? AnalysisStatus.FAILED.name() : AnalysisStatus.PENDING.name(),
                noImage ? "NO_IMAGE" : null,
                now);
        return new InputReconciliation(row.getItemId(), !noImage);
    }

    @Override
    @Transactional
    public StateMutation replaceByAdministrator(
            long packageId,
            String skuId,
            long expectedVersion,
            String factsJson,
            Instant now) {
        mapper.ensureSkuItem(packageId, skuId, VisualInputFingerprint.sha256(""), now);
        VisionStateRow row = mapper.lockSkuState(packageId, skuId);
        if (active(row.getAnalysisStatus())) {
            return StateMutation.ACTIVE_CONFLICT;
        }
        if (row.getFactVersion() != expectedVersion) {
            return StateMutation.VERSION_CONFLICT;
        }
        int changed;
        if (expectedVersion == 0) {
            changed = mapper.insertAdministratorFacts(
                    packageId,
                    skuId,
                    row.getInputFingerprint(),
                    factsJson,
                    now);
        } else {
            changed = mapper.replaceAdministratorFacts(packageId, skuId, expectedVersion, factsJson, now);
        }
        return changed == 1 ? StateMutation.APPLIED : StateMutation.VERSION_CONFLICT;
    }

    @Override
    @Transactional
    public StateMutation requestReanalysis(
            long packageId,
            String skuId,
            long expectedGeneration,
            String requestId,
            String inputFingerprint,
            boolean noImage,
            Instant now) {
        mapper.ensureSkuItem(packageId, skuId, inputFingerprint, now);
        VisionStateRow row = mapper.lockSkuState(packageId, skuId);
        // 同一次点击的重试必须先于 generation 和 active 判断。
        if (requestId.equals(row.getLastRequestId())) {
            return StateMutation.IDEMPOTENT;
        }
        if (row.getAnalysisGeneration() != expectedGeneration) {
            return StateMutation.GENERATION_CONFLICT;
        }
        if (active(row.getAnalysisStatus())) {
            return StateMutation.ACTIVE_CONFLICT;
        }
        int changed = mapper.resetForReanalysis(
                packageId,
                skuId,
                expectedGeneration,
                requestId,
                inputFingerprint,
                row.getFactVersion(),
                noImage ? AnalysisStatus.FAILED.name() : AnalysisStatus.PENDING.name(),
                noImage ? "NO_IMAGE" : null,
                now);
        return changed == 1 ? StateMutation.APPLIED : StateMutation.GENERATION_CONFLICT;
    }

    private VisionStateSnapshot empty(long packageId, String skuId) {
        return new VisionStateSnapshot(
                packageId,
                skuId,
                VisualInputFingerprint.sha256(""),
                null,
                0,
                null,
                null,
                AnalysisStatus.FAILED,
                0,
                0,
                0,
                0,
                null,
                "NOT_ANALYZED");
    }

    private VisionStateSnapshot snapshot(VisionStateRow row) {
        return new VisionStateSnapshot(
                row.getPackageId(),
                row.getSkuId(),
                row.getInputFingerprint(),
                row.getFactsJson(),
                row.getFactVersion(),
                row.getWriter(),
                row.getFactsUpdatedAt(),
                row.getAnalysisStatus(),
                row.getAnalysisGeneration(),
                row.getRunEpoch(),
                row.getProviderAttemptCount(),
                row.getStructureRoundCount(),
                row.getLastRequestId(),
                row.getFailureCode());
    }

    private boolean active(AnalysisStatus status) {
        return status == AnalysisStatus.PENDING
                || status == AnalysisStatus.RUNNING
                || status == AnalysisStatus.EXPIRED;
    }
}
