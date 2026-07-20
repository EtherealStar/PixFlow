package com.pixflow.module.memory.recall;

import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.module.file.api.AssetReferenceExpander;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.AssetUse;
import com.pixflow.module.file.api.ResolvedAssetReference;
import com.pixflow.module.memory.context.MemoryReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FileRecallReferenceResolver implements RecallReferenceResolver {
    private final AssetReferenceResolver resolver;

    private final AssetReferenceExpander expander;

    private final int maxPackageImages;

    public FileRecallReferenceResolver(
            AssetReferenceResolver resolver,
            AssetReferenceExpander expander,
            int maxPackageImages) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.expander = Objects.requireNonNull(expander, "expander");
        if (maxPackageImages <= 0) {
            throw new IllegalArgumentException("maxPackageImages must be positive");
        }
        this.maxPackageImages = maxPackageImages;
    }

    @Override
    public ResolvedRecallReferences resolve(List<MemoryReference> references) {
        LinkedHashSet<String> skuIds = new LinkedHashSet<>();
        List<Map<String, Object>> trace = new ArrayList<>();
        if (references == null) {
            return new ResolvedRecallReferences(List.of(), List.of(), trace);
        }
        for (MemoryReference reference : references) {
            try {
                ResolvedAssetReference resolved = resolver.resolve(reference.referenceKey(), AssetUse.INSPECT);
                if (resolved.kind() == AssetReferenceKind.PACKAGE) {
                    List<ResolvedAssetReference> images = expander
                            .expand(
                                    List.of(reference.referenceKey()),
                                    AssetUse.INSPECT,
                                    maxPackageImages + 1)
                            .images();
                    boolean hasSku = false;
                    for (ResolvedAssetReference image : images.stream().limit(maxPackageImages).toList()) {
                        if (image.skuId() != null && !image.skuId().isBlank()) {
                            hasSku = true;
                            addSku(skuIds, image.skuId());
                        }
                    }
                    if (images.size() > maxPackageImages) {
                        trace.add(Map.of(
                                "reference_key", reference.referenceKey(),
                                "status", "truncated",
                                "truncated", true));
                        continue;
                    }
                    if (images.isEmpty()) {
                        trace.add(Map.of(
                                "reference_key", reference.referenceKey(),
                                "status", "empty_package"));
                        continue;
                    }
                    if (!hasSku) {
                        trace.add(Map.of(
                                "reference_key", reference.referenceKey(),
                                "status", "sku_unavailable"));
                        continue;
                    }
                } else {
                    addSku(skuIds, resolved.skuId());
                    if (resolved.skuId() == null || resolved.skuId().isBlank()) {
                        trace.add(Map.of(
                                "reference_key", reference.referenceKey(),
                                "status", "sku_unavailable"));
                        continue;
                    }
                }
                trace.add(Map.of("reference_key", reference.referenceKey(), "status", "resolved"));
            } catch (RuntimeException ignored) {
                // 单条素材解析失败只降级该召回信号，不能阻断对话。
                trace.add(Map.of(
                        "reference_key", reference.referenceKey(),
                        "status", "resolution_failed"));
            }
        }
        return new ResolvedRecallReferences(List.copyOf(skuIds), List.of(), trace);
    }

    private static void addSku(LinkedHashSet<String> skuIds, String skuId) {
        if (skuId != null && !skuId.isBlank()) {
            skuIds.add(skuId.trim());
        }
    }
}
