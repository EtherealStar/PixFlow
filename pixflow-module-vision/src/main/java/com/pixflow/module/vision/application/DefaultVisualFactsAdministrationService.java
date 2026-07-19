package com.pixflow.module.vision.application;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.vision.api.ReanalyzeVisualFactsCommand;
import com.pixflow.module.vision.api.ReplaceVisualFactsCommand;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.api.VisualFactsView;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.domain.StateMutation;
import com.pixflow.module.vision.domain.VisionStateSnapshot;
import com.pixflow.module.vision.domain.VisionStateStore;
import com.pixflow.module.vision.domain.VisualInputFingerprint;
import com.pixflow.module.vision.error.VisionErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultVisualFactsAdministrationService implements VisualFactsAdministrationService {
    private final VisionStateStore stateStore;

    private final VisualAssetReader assetReader;

    private final ProductVisualFactsNormalizer normalizer;

    private final Clock clock;

    public DefaultVisualFactsAdministrationService(
            VisionStateStore stateStore,
            VisualAssetReader assetReader,
            ProductVisualFactsNormalizer normalizer,
            Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.assetReader = Objects.requireNonNull(assetReader, "assetReader");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VisualFactsView get(long packageId, String skuId) {
        validateIdentity(packageId, skuId);
        return view(stateStore.get(packageId, skuId));
    }

    @Override
    public VisualFactsView replace(long packageId, String skuId, ReplaceVisualFactsCommand command) {
        validateIdentity(packageId, skuId);
        Objects.requireNonNull(command, "command");
        StateMutation mutation = stateStore.replaceByAdministrator(
                packageId,
                skuId,
                command.expectedVersion(),
                normalizer.write(command.facts()),
                clock.instant());
        if (mutation == StateMutation.ACTIVE_CONFLICT) {
            throw conflict(VisionErrorCode.VISUAL_ANALYSIS_ACTIVE, packageId, skuId);
        }
        if (mutation != StateMutation.APPLIED) {
            throw conflict(VisionErrorCode.VISUAL_FACTS_VERSION_CONFLICT, packageId, skuId);
        }
        return view(stateStore.get(packageId, skuId));
    }

    @Override
    public VisualFactsView reanalyze(
            long packageId,
            String skuId,
            ReanalyzeVisualFactsCommand command) {
        validateIdentity(packageId, skuId);
        Objects.requireNonNull(command, "command");
        List<VisualAsset> assets = assetReader.listCurrentOriginals(packageId).stream()
                .filter(asset -> skuId.equals(asset.skuId()))
                .toList();
        StateMutation mutation = stateStore.requestReanalysis(
                packageId,
                skuId,
                command.expectedGeneration(),
                command.requestId(),
                VisualInputFingerprint.forSku(assets),
                assets.isEmpty(),
                clock.instant());
        if (mutation == StateMutation.GENERATION_CONFLICT) {
            throw conflict(VisionErrorCode.VISUAL_ANALYSIS_GENERATION_CONFLICT, packageId, skuId);
        }
        if (mutation == StateMutation.ACTIVE_CONFLICT) {
            throw conflict(VisionErrorCode.VISUAL_ANALYSIS_ACTIVE, packageId, skuId);
        }
        return view(stateStore.get(packageId, skuId));
    }

    private VisualFactsView view(VisionStateSnapshot snapshot) {
        return new VisualFactsView(
                snapshot.packageId(),
                snapshot.skuId(),
                snapshot.analysisStatus(),
                snapshot.analysisGeneration(),
                snapshot.factsJson() == null ? null : normalizer.read(snapshot.factsJson()),
                snapshot.factVersion(),
                snapshot.writer(),
                snapshot.factsUpdatedAt(),
                snapshot.failureCode());
    }

    private PixFlowException conflict(VisionErrorCode code, long packageId, String skuId) {
        return new PixFlowException(
                code,
                code.code(),
                null,
                Map.of("packageId", packageId, "skuId", skuId));
    }

    private void validateIdentity(long packageId, String skuId) {
        if (packageId <= 0 || skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("packageId and skuId must identify a SKU scope");
        }
    }
}
