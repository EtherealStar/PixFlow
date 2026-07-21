package com.pixflow.app.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.harness.tools.ToolCallClassification;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolResultPolicy;
import com.pixflow.module.conversation.proposal.PendingProposalType;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.conversation.proposal.ProposalView;
import com.pixflow.module.conversation.proposal.PublishProposalCommand;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.propose.DagProposalService;
import com.pixflow.module.dag.propose.ValidatedImagePlan;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App 对 submit_image_plan 的跨 owner 编排适配器。 */
public final class SubmitImagePlanHandler {

    public static final String TOOL_NAME = "submit_image_plan";

    private final DagProposalService planService;

    private final ProposalService proposalService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final CanonicalAssetReferenceCodec referenceCodec =
            new CanonicalAssetReferenceCodec();

    public SubmitImagePlanHandler(
            DagProposalService planService,
            ProposalService proposalService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.planService = planService;
        this.proposalService = proposalService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ToolDescriptor submitImagePlanDescriptor() {
        Map<String, Object> dagSchema = Map.of(
                "type", "object",
                "required", List.of("nodes", "edges"),
                "properties", Map.of(
                        "nodes", Map.of("type", "array"),
                        "edges", Map.of("type", "array")));
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("referenceKeys", "dag"),
                "properties", Map.of(
                        "referenceKeys", Map.of(
                                "type", "array", "minItems", 1,
                                "items", Map.of("type", "string", "minLength", 1)),
                        "dag", dagSchema,
                        "note", Map.of("type", "string", "maxLength", 1024)));
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "proposalId", Map.of("type", "string"),
                        "payloadHash", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "summary", Map.of("type", "string")));
        ToolHandler handler = this::handle;
        return new ToolDescriptor(
                TOOL_NAME,
                "提交已校验 Image Plan，等待用户逐项确认。",
                inputSchema,
                outputSchema,
                "submit_image_plan 只接受 canonical referenceKeys 与 DAG。",
                false,
                handler,
                (descriptor, arguments) -> new ToolCallClassification(
                        false, true, TOOL_NAME, Map.of(), ToolResultPolicy.defaults()),
                (descriptor, arguments) -> validateInput(arguments),
                ToolResultPolicy.defaults());
    }

    private static void validateInput(Map<String, Object> arguments) {
        Object dag = arguments == null ? null : arguments.get("dag");
        if (!(dag instanceof Map<?, ?> dagMap)
                || !dagMap.containsKey("nodes") || !dagMap.containsKey("edges")) {
            throw new IllegalArgumentException("dag 必须包含 nodes/edges");
        }
        readReferenceKeys(arguments.get("referenceKeys"));
    }

    ToolHandlerOutput handle(ToolInvocation invocation) {
        try {
            Map<String, Object> arguments = invocation.arguments();
            Object dagObject = arguments == null ? null : arguments.get("dag");
            if (!(dagObject instanceof Map<?, ?> dagMap)
                    || !dagMap.containsKey("nodes") || !dagMap.containsKey("edges")) {
                return error("DAG_INVALID_STRUCTURE", "dag 必须包含 nodes/edges");
            }
            List<String> referenceKeys = readReferenceKeys(arguments.get("referenceKeys"));
            List<AssetReferenceKey> references = referenceKeys.stream()
                    .map(referenceCodec::parse)
                    .toList();
            DagDocument document = planService.parseDocument(
                    objectMapper.writeValueAsString(dagObject));
            ValidatedImagePlan validated = planService.validate(document, references);
            invocation.proposalPublicationAuthorizer().authorize(
                    "DAG", referenceKeys, validated.payloadHash());
            ProposalView proposal = proposalService.publish(new PublishProposalCommand(
                    PendingProposalType.DAG,
                    invocation.conversationId(),
                    validated.packageId(),
                    invocation.toolCallId(),
                    validated.canonicalPayload(),
                    validated.payloadHash(),
                    0,
                    referenceKeys,
                    clock.instant()));
            ProposalReadyEvent readyEvent = new ProposalReadyEvent(
                    proposal.proposalId(),
                    invocation.conversationId(),
                    "IMAGE_PROCESS",
                    "处理素材",
                    "图片处理方案已准备完成",
                    List.of("已选择 " + referenceKeys.size() + " 项素材"),
                    clock.instant());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("proposalId", proposal.proposalId());
            result.put("payloadHash", proposal.payloadHash());
            result.put("status", "PENDING");
            result.put("summary", "已发布，等待用户确认");
            // trace 只持久化受控 metadata；显式携带 owner hash，供离线评估绑定确认决定。
            return new ToolHandlerOutput(
                    objectMapper.writeValueAsString(result),
                    Map.of(
                            "payloadHash", proposal.payloadHash(),
                            ProposalReadyEvent.METADATA_KEY, readyEvent));
        } catch (PixFlowException exception) {
            return error(exception.code().code(), exception.getMessage());
        } catch (Exception exception) {
            return error("DAG_INVALID_STRUCTURE", exception.getMessage());
        }
    }

    private static List<String> readReferenceKeys(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()
                || raw.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("referenceKeys 必须是非空字符串数组");
        }
        return raw.stream().map(String.class::cast).toList();
    }

    private ToolHandlerOutput error(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", code + ": " + Sanitizer.sanitizeMessage(message));
        try {
            return ToolHandlerOutput.of(objectMapper.writeValueAsString(error));
        } catch (Exception serializationFailure) {
            return ToolHandlerOutput.of("{\"error\":\"DAG_INVALID_STRUCTURE: 内部错误\"}");
        }
    }
}
