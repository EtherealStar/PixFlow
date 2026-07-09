package com.pixflow.module.dag.propose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.proposal.PendingPlanProposal;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.ir.DagDocument;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingPlanPortAdapterTest {
    private PendingPlanMapper mapper;
    private PendingPlanService service;
    private PendingPlanPortAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(PendingPlanMapper.class);
        service = mock(PendingPlanService.class);
        adapter = new PendingPlanPortAdapter(mapper, service, new DagProperties());
    }

    @Test
    void enqueueImagegen_insertsNeutralPendingPlan() {
        when(mapper.findByToolCallId("tc-1")).thenReturn(null);
        when(mapper.insert(any(PendingPlan.class))).thenAnswer(inv -> {
            PendingPlan plan = inv.getArgument(0);
            plan.setId(42L);
            return 1;
        });

        String planId = adapter.enqueue(new PendingPlanProposal(
                "IMAGEGEN",
                "{\"sourceImageIds\":[\"img-1\"]}",
                "conv-1",
                "pkg-1",
                "tc-1",
                Instant.parse("2026-07-06T00:00:00Z")));

        assertThat(planId).isEqualTo("42");
        verify(mapper).insert(any(PendingPlan.class));
        verify(service, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void enqueueImagegen_isIdempotentByToolCallId() {
        PendingPlan existing = new PendingPlan();
        existing.setId(99L);
        when(mapper.findByToolCallId("tc-1")).thenReturn(existing);

        String planId = adapter.enqueue(new PendingPlanProposal(
                "IMAGEGEN", "{\"x\":1}", "conv-1", "pkg-1", "tc-1", Instant.now()));

        assertThat(planId).isEqualTo("99");
        verify(mapper, never()).insert(any(PendingPlan.class));
    }

    @Test
    void enqueueImagePlan_delegatesToPendingPlanServiceForDeepValidation() {
        DagDocument document = mock(DagDocument.class);
        PendingPlan stored = new PendingPlan();
        stored.setId(7L);
        when(service.parseDocument("{\"nodes\":[],\"edges\":[]}")).thenReturn(document);
        when(service.enqueue("tc-dag", "conv-1", document, "packageId=pkg-1")).thenReturn(stored);

        String planId = adapter.enqueue(new PendingPlanProposal(
                "IMAGE_PLAN",
                "{\"nodes\":[],\"edges\":[]}",
                "conv-1",
                "pkg-1",
                "tc-dag",
                Instant.now()));

        assertThat(planId).isEqualTo("7");
        verify(service).enqueue("tc-dag", "conv-1", document, "packageId=pkg-1");
        verify(mapper, never()).insert(any(PendingPlan.class));
    }

    @Test
    void find_returnsProposalFromPendingPlanRow() {
        PendingPlan plan = new PendingPlan();
        plan.setId(42L);
        plan.setType("IMAGEGEN");
        plan.setDagJson("{\"sourceImageIds\":[\"img-1\"]}");
        plan.setConversationId("conv-1");
        plan.setToolCallId("tc-1");
        plan.setCreatedAt(Instant.parse("2026-07-06T00:00:00Z"));
        plan.setNote("packageId=pkg-1");
        when(mapper.findById(42L)).thenReturn(plan);

        Optional<com.pixflow.contracts.proposal.PendingPlanProposal> found = adapter.find("42");

        assertThat(found).isPresent();
        assertThat(found.get().planType()).isEqualTo("IMAGEGEN");
        assertThat(found.get().packageId()).isEqualTo("pkg-1");
        assertThat(found.get().payloadJson()).contains("sourceImageIds");
    }
}
