package com.pixflow.module.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.module.file.api.AssetReferenceExpander;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.api.AssetUse;
import com.pixflow.module.file.api.ExpandedAssetSet;
import com.pixflow.module.file.api.ResolvedAssetReference;
import com.pixflow.module.memory.context.MemoryReference;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileRecallReferenceResolverTest {

    @Test
    void resolvesPackageSkuAndBothImageKindsInReferenceOrder() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        AssetReferenceExpander expander = mock(AssetReferenceExpander.class);
        when(resolver.resolve("package", AssetUse.INSPECT)).thenReturn(reference(
                "package", AssetReferenceKind.PACKAGE, null, 1L, null, null));
        when(resolver.resolve("sku", AssetUse.INSPECT)).thenReturn(reference(
                "sku", AssetReferenceKind.SKU, null, 1L, null, "SKU-B"));
        when(resolver.resolve("original", AssetUse.INSPECT)).thenReturn(reference(
                "original", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 11L, "SKU-C"));
        when(resolver.resolve("generated", AssetUse.INSPECT)).thenReturn(reference(
                "generated", AssetReferenceKind.IMAGE, AssetSourceType.GENERATED, 1L, 12L, "SKU-D"));
        when(expander.expand(anyList(), eq(AssetUse.INSPECT), eq(11))).thenReturn(new ExpandedAssetSet(List.of(
                reference("p1", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 1L, "SKU-A"),
                reference("p2", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 2L, "SKU-B"))));

        ResolvedRecallReferences result = new FileRecallReferenceResolver(resolver, expander, 10).resolve(List.of(
                memoryReference("package"), memoryReference("sku"), memoryReference("original"),
                memoryReference("generated")));

        assertThat(result.skuIds()).containsExactly("SKU-A", "SKU-B", "SKU-C", "SKU-D");
        assertThat(result.trace()).allMatch(item -> "resolved".equals(item.get("status")));
    }

    @Test
    void capsPackageExpansionAndKeepsOtherReferencesWhenOneFails() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        AssetReferenceExpander expander = mock(AssetReferenceExpander.class);
        when(resolver.resolve("bad", AssetUse.INSPECT)).thenThrow(new IllegalStateException("secret path"));
        when(resolver.resolve("package", AssetUse.INSPECT)).thenReturn(reference(
                "package", AssetReferenceKind.PACKAGE, null, 1L, null, null));
        when(expander.expand(anyList(), eq(AssetUse.INSPECT), eq(3))).thenReturn(new ExpandedAssetSet(List.of(
                reference("p1", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 1L, "SKU-A"),
                reference("p2", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 2L, "SKU-B"),
                reference("p3", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 3L, "SKU-C"))));

        ResolvedRecallReferences result = new FileRecallReferenceResolver(resolver, expander, 2).resolve(List.of(
                memoryReference("bad"), memoryReference("package")));

        assertThat(result.skuIds()).containsExactly("SKU-A", "SKU-B");
        assertThat(result.trace()).containsExactly(
                java.util.Map.of("reference_key", "bad", "status", "resolution_failed"),
                java.util.Map.of("reference_key", "package", "status", "truncated", "truncated", true));
        assertThat(result.trace().toString()).doesNotContain("secret path");
        verify(expander).expand(List.of("package"), AssetUse.INSPECT, 3);
    }

    @Test
    void reportsEmptyPackageAndImageWithoutSkuAsDegradedSignals() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        AssetReferenceExpander expander = mock(AssetReferenceExpander.class);
        when(resolver.resolve("empty", AssetUse.INSPECT)).thenReturn(reference(
                "empty", AssetReferenceKind.PACKAGE, null, 1L, null, null));
        when(resolver.resolve("image", AssetUse.INSPECT)).thenReturn(reference(
                "image", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL, 1L, 11L, null));
        when(expander.expand(List.of("empty"), AssetUse.INSPECT, 11))
                .thenReturn(new ExpandedAssetSet(List.of()));

        ResolvedRecallReferences result = new FileRecallReferenceResolver(resolver, expander, 10)
                .resolve(List.of(memoryReference("empty"), memoryReference("image")));

        assertThat(result.skuIds()).isEmpty();
        assertThat(result.trace()).containsExactly(
                java.util.Map.of("reference_key", "empty", "status", "empty_package"),
                java.util.Map.of("reference_key", "image", "status", "sku_unavailable"));
    }

    @Test
    void reportsPackageWithoutAnySkuAsDegradedSignal() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        AssetReferenceExpander expander = mock(AssetReferenceExpander.class);
        when(resolver.resolve("package", AssetUse.INSPECT)).thenReturn(reference(
                "package", AssetReferenceKind.PACKAGE, null, 1L, null, null));
        when(expander.expand(List.of("package"), AssetUse.INSPECT, 11))
                .thenReturn(new ExpandedAssetSet(List.of(
                        reference("image", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL,
                                1L, 11L, null))));

        ResolvedRecallReferences result = new FileRecallReferenceResolver(resolver, expander, 10)
                .resolve(List.of(memoryReference("package")));

        assertThat(result.skuIds()).isEmpty();
        assertThat(result.trace()).containsExactly(
                java.util.Map.of("reference_key", "package", "status", "sku_unavailable"));
    }

    private static MemoryReference memoryReference(String key) {
        return new MemoryReference(key, key);
    }

    private static ResolvedAssetReference reference(
            String key,
            AssetReferenceKind kind,
            AssetSourceType sourceType,
            long packageId,
            Long imageId,
            String skuId) {
        return new ResolvedAssetReference(key, kind, sourceType, packageId, imageId, skuId, key);
    }
}
