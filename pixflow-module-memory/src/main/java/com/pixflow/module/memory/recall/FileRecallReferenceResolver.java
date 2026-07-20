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

public final class FileRecallReferenceResolver implements RecallReferenceResolver {
    private final AssetReferenceResolver resolver;
    private final AssetReferenceExpander expander;

    public FileRecallReferenceResolver(AssetReferenceResolver resolver, AssetReferenceExpander expander) {
        this.resolver = resolver;
        this.expander = expander;
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
                    expander.expand(List.of(reference.referenceKey()), AssetUse.INSPECT).images()
                            .forEach(image -> addSku(skuIds, image.skuId()));
                } else {
                    addSku(skuIds, resolved.skuId());
                }
                trace.add(Map.of("reference_key", reference.referenceKey(), "status", "resolved"));
            } catch (RuntimeException ignored) {
                // 单条素材解析失败只降级该召回信号，不能阻断对话。
                trace.add(Map.of("reference_key", reference.referenceKey(), "status", "unresolved"));
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
