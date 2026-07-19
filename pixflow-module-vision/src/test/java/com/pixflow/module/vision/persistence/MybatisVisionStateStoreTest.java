package com.pixflow.module.vision.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.domain.InputReconciliation;
import com.pixflow.module.vision.domain.StateMutation;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisVisionStateStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Mock
    private VisionStateMapper mapper;

    private MybatisVisionStateStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisVisionStateStore(mapper);
    }

    @Test
    void administratorReplacementIsRejectedWhileAnalysisIsActive() {
        VisionStateRow row = row(AnalysisStatus.RUNNING, 3, 2, "old-request", "a".repeat(64));
        when(mapper.lockSkuState(7, "sku")).thenReturn(row);

        StateMutation result = store.replaceByAdministrator(7, "sku", 2, "{}", NOW);

        assertThat(result).isEqualTo(StateMutation.ACTIVE_CONFLICT);
        verify(mapper, never()).replaceAdministratorFacts(7, "sku", 2, "{}", NOW);
    }

    @Test
    void repeatedReanalysisRequestIsIdempotentBeforeGenerationAndActiveChecks() {
        VisionStateRow row = row(AnalysisStatus.RUNNING, 9, 2, "same-click", "a".repeat(64));
        when(mapper.lockSkuState(7, "sku")).thenReturn(row);

        StateMutation result = store.requestReanalysis(
                7,
                "sku",
                1,
                "same-click",
                "a".repeat(64),
                false,
                NOW);

        assertThat(result).isEqualTo(StateMutation.IDEMPOTENT);
        verify(mapper, never()).resetForReanalysis(
                7, "sku", 1, "same-click", "a".repeat(64), 2, "PENDING", null, NOW);
    }

    @Test
    void changedContentClearsFactsBeforeQueuingNextGeneration() {
        VisionStateRow before = row(AnalysisStatus.SUCCEEDED, 4, 5, null, "a".repeat(64));
        VisionStateRow afterInvalidation = row(AnalysisStatus.SUCCEEDED, 4, 6, null, "a".repeat(64));
        when(mapper.lockSkuState(7, "sku")).thenReturn(before, afterInvalidation);
        when(mapper.resetForInput(41, "b".repeat(64), 6, "PENDING", null, NOW)).thenReturn(1);

        InputReconciliation result = store.reconcileSkuInput(
                7,
                "sku",
                "b".repeat(64),
                false,
                NOW);

        assertThat(result).isEqualTo(new InputReconciliation(41, true));
        verify(mapper).invalidateFactsForInput(7, "sku", "b".repeat(64), NOW);
        verify(mapper).resetForInput(41, "b".repeat(64), 6, "PENDING", null, NOW);
    }

    @Test
    void unchangedContentDoesNotAdvanceGeneration() {
        VisionStateRow row = row(AnalysisStatus.SUCCEEDED, 4, 5, null, "a".repeat(64));
        when(mapper.lockSkuState(7, "sku")).thenReturn(row);

        InputReconciliation result = store.reconcileSkuInput(
                7,
                "sku",
                "a".repeat(64),
                false,
                NOW);

        assertThat(result).isEqualTo(new InputReconciliation(41, false));
        verify(mapper, never()).invalidateFactsForInput(7, "sku", "a".repeat(64), NOW);
        verify(mapper, never()).resetForInput(41, "a".repeat(64), 5, "PENDING", null, NOW);
    }

    private VisionStateRow row(
            AnalysisStatus status,
            long generation,
            long version,
            String requestId,
            String fingerprint) {
        VisionStateRow row = new VisionStateRow();
        row.setItemId(41);
        row.setPackageId(7);
        row.setSkuId("sku");
        row.setAnalysisStatus(status);
        row.setAnalysisGeneration(generation);
        row.setFactVersion(version);
        row.setLastRequestId(requestId);
        row.setInputFingerprint(fingerprint);
        return row;
    }
}
