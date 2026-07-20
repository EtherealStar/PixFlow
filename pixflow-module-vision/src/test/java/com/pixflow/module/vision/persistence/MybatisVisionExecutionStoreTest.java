package com.pixflow.module.vision.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.vision.execution.VisionWorkItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MybatisVisionExecutionStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");

    @Test
    void versionZeroUsesInsertAndCompletesOnlyAfterFactsWrite() {
        VisionStateMapper mapper = mock(VisionStateMapper.class);
        MybatisVisionExecutionStore store = new MybatisVisionExecutionStore(mapper);
        VisionWorkItem item = item(0);
        when(mapper.insertAiSkuFacts(7L, "SKU-1", "hash", "{}", "{}", NOW)).thenReturn(1);
        when(mapper.completeWorkItem(9L, 3L, 2L, NOW)).thenReturn(1);

        assertThat(store.commitFacts(item, "{}", "{}", NOW)).isTrue();

        verify(mapper, never()).updateAiSkuFacts(
                7L, "SKU-1", "hash", 0, "{}", "{}", NOW);
        verify(mapper).refreshJob(7L, NOW);
    }

    @Test
    void factVersionConflictDoesNotCompleteWorkItem() {
        VisionStateMapper mapper = mock(VisionStateMapper.class);
        MybatisVisionExecutionStore store = new MybatisVisionExecutionStore(mapper);
        VisionWorkItem item = item(4);
        when(mapper.updateAiSkuFacts(7L, "SKU-1", "hash", 4, "{}", "{}", NOW)).thenReturn(0);

        assertThat(store.commitFacts(item, "{}", "{}", NOW)).isFalse();

        verify(mapper, never()).completeWorkItem(9L, 3L, 2L, NOW);
        verify(mapper, never()).refreshJob(7L, NOW);
    }

    private static VisionWorkItem item(long factVersion) {
        return new VisionWorkItem(9L, 7L, "SKU-1", "SKU", 0L, "hash", "RUNNING",
                3L, 2L, factVersion, 1, 1);
    }
}
