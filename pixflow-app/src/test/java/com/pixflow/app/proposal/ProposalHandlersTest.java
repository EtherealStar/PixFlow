package com.pixflow.app.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolRuntimeContext;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.propose.DagProposalService;
import com.pixflow.module.dag.propose.ValidatedImagePlan;
import com.pixflow.module.imagegen.proposal.ImagegenPlanService;
import com.pixflow.module.imagegen.proposal.ValidatedRedrawRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProposalHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void dagAdapterPublishesValidatedPayloadAndDescriptorUsesReferenceKeys() {
        DagProposalService producer = mock(DagProposalService.class);
        DagDocument document = new DagDocument(List.of(), List.of());
        when(producer.parseDocument(any())).thenReturn(document);
        when(producer.validate(any(), any())).thenReturn(new ValidatedImagePlan(
                "{\"schemaVersion\":\"1.0\"}",
                "dag-hash",
                7L,
                List.of(new ImageAssetReferenceKey(7L, 11L))));
        SubmitImagePlanHandler handler = new SubmitImagePlanHandler(
                producer, new ProposalService(), objectMapper, clock);

        String output = handler.handle(invocation(
                "tool-dag",
                "submit_image_plan",
                Map.of(
                        "referenceKeys", List.of("package:7/image:11"),
                        "dag", Map.of("nodes", List.of(), "edges", List.of())))).content();

        assertThat(output).contains("\"payloadHash\":\"dag-hash\"");
        assertThat(handler.submitImagePlanDescriptor().inputSchema().toString())
                .contains("referenceKeys")
                .doesNotContain("imageIds");
    }

    @Test
    void imagegenAdapterPublishesOneImageAndToolCallReplayIsIdempotent() throws Exception {
        ImagegenPlanService producer = mock(ImagegenPlanService.class);
        when(producer.validate(any(), any())).thenReturn(new ValidatedRedrawRequest(
                new ImageAssetReferenceKey(7L, 11L),
                "{\"sourceReferenceKey\":\"package:7/image:11\"}",
                "redraw-hash"));
        ProposalService proposals = new ProposalService();
        SubmitImagegenPlanHandler handler = new SubmitImagegenPlanHandler(
                producer, proposals, objectMapper, clock);
        ToolInvocation invocation = invocation(
                "tool-redraw",
                "submit_imagegen_plan",
                Map.of("referenceKey", "package:7/image:11", "prompt", "重绘"));

        String first = handler.handle(invocation).content();
        String replay = handler.handle(invocation).content();

        assertThat(objectMapper.readTree(replay).path("proposalId").asText())
                .isEqualTo(objectMapper.readTree(first).path("proposalId").asText());
        assertThat(handler.submitImagegenPlanDescriptor().inputSchema().toString())
                .contains("referenceKey")
                .doesNotContain("source_image_ids");
    }

    private static ToolInvocation invocation(
            String toolCallId, String toolName, Map<String, Object> arguments) {
        return new ToolInvocation(
                toolCallId,
                toolName,
                arguments,
                "conversation-1",
                1,
                "trace-1",
                RuntimeScope.main(),
                ToolRuntimeContext.unavailable(),
                (proposalType, referenceKeys, payloadHash) -> {
                },
                Map.of());
    }
}
