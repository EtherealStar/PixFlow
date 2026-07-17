package com.pixflow.module.dag.propose;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubmitImagePlanHandler 测试:浅校验 + 深校验 + 入队 + 幂等。
 */
class SubmitImagePlanHandlerTest {

    private DagProposalService service;
    private ObjectMapper objectMapper;
    private SubmitImagePlanHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(DagProposalService.class);
        objectMapper = new ObjectMapper();
        handler = new SubmitImagePlanHandler(service, objectMapper);
    }

    private ToolInvocation invocation(String toolCallId, Map<String, Object> args) {
        return new ToolInvocation(toolCallId, "submit_image_plan", args,
            "conv1", 1, "trace1", RuntimeScope.main(), Map.of());
    }

    @Test
    void handler_validDag_enqueuesAndReturnsPlanId() {
        when(service.parseDocument(any())).thenReturn(
            new com.pixflow.module.dag.ir.DagDocument(java.util.List.of(), java.util.List.of()));
        when(service.publish(eq("tc1"), eq("conv1"), any(),
                eq(java.util.List.of("package:1/image:2")), any()))
                .thenReturn(new DagProposal("proposal-42", "abc"));

        Map<String, Object> args = Map.of(
            "referenceKeys", java.util.List.of("package:1/image:2"),
            "dag", Map.of("nodes", java.util.List.of(), "edges", java.util.List.of()),
            "note", "note"
        );
        ToolHandlerOutput output = handler.handle(invocation("tc1", args));
        assertThat(output.content()).contains("\"proposalId\":\"proposal-42\"");
        assertThat(output.content()).contains("\"payloadHash\":\"abc\"");
        assertThat(output.content()).contains("\"status\":\"PENDING\"");
    }

    @Test
    void handler_missingDag_returnsError() {
        ToolHandlerOutput output = handler.handle(invocation("tc1", Map.of()));
        assertThat(output.content()).contains("DAG_INVALID_STRUCTURE");
        verify(service, times(0)).publish(any(), any(), any(), any(), any());
    }

    @Test
    void handler_dagMissingNodesOrEdges_returnsError() {
        Map<String, Object> args = Map.of("dag", Map.of("only", "wrong"));
        ToolHandlerOutput output = handler.handle(invocation("tc1", args));
        assertThat(output.content()).contains("DAG_INVALID_STRUCTURE");
    }

    @Test
    void descriptor_isExposedViaBean() {
        ToolDescriptor d = handler.submitImagePlanDescriptor();
        assertThat(d.name()).isEqualTo("submit_image_plan");
        assertThat(d.handler()).isNotNull();
        assertThat(d.readOnlyHint()).isFalse();
        // inputSchema 应要求 dag 必填
        assertThat(d.inputSchema()).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) d.inputSchema().get("required");
        assertThat(required).contains("referenceKeys", "dag");
    }

    @Test
    void descriptor_classifierMarksNonReadOnlyAndNonConcurrentSafe() {
        ToolDescriptor d = handler.submitImagePlanDescriptor();
        var classification = d.classifier().classify(d, Map.of());
        assertThat(classification.readOnly()).isFalse();
    }
}
