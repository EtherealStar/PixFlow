package com.pixflow.module.vision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.vision.api.CommonVisualFacts;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.execution.VisionExecutionStore;
import com.pixflow.module.vision.execution.VisionFactsWorker;
import com.pixflow.module.vision.execution.VisionWorkItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FocusedImageAnalysisTest {
    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void executesPendingImageWorkInlineAndReturnsPersistedFacts() {
        VisionExecutionStore store = mock(VisionExecutionStore.class);
        VisionFactsWorker worker = mock(VisionFactsWorker.class);
        ProductVisualFactsNormalizer normalizer = new ProductVisualFactsNormalizer(new ObjectMapper());
        VisualAsset asset = new VisualAsset(
                7L, "SKU-1", 8L, HASH, 12L, "image/png", () -> null);
        VisionWorkItem pending = new VisionWorkItem(
                9L, 7L, "SKU-1", "IMAGE", 8L, HASH, "PENDING", 1, 0, 0, 0, 0);
        VisionWorkItem success = new VisionWorkItem(
                9L, 7L, "SKU-1", "IMAGE", 8L, HASH, "SUCCESS", 1, 1, 0, 1, 1);
        ProductVisualFacts facts = new ProductVisualFacts(
                new CommonVisualFacts("bottle", List.of("blue"), List.of(), List.of(),
                        List.of(), List.of(), List.of(), "white", List.of("front")),
                List.of(), List.of(), List.of());
        when(store.ensureImageWork(asset, NOW)).thenReturn(pending);
        when(store.currentImageFacts(7L, "SKU-1", 8L, HASH))
                .thenReturn(null, normalizer.write(facts));
        when(store.get(9L)).thenReturn(success);

        FocusedImageAnalysis.Result result = new FocusedImageAnalysis(
                store, worker, normalizer, Clock.fixed(NOW, ZoneOffset.UTC)).analyze(asset);

        verify(worker).execute(9L);
        assertThat(result.active()).isFalse();
        assertThat(result.facts()).isEqualTo(normalizer.normalize(facts));
    }
}
