package com.pixflow.app.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
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
import com.pixflow.module.imagegen.proposal.ImagegenPlanInputs;
import com.pixflow.module.imagegen.proposal.ImagegenPlanService;
import com.pixflow.module.imagegen.proposal.ValidatedRedrawRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App 对 submit_imagegen_plan 的单图跨 owner 编排适配器。 */
public final class SubmitImagegenPlanHandler {

    public static final String TOOL_NAME = "submit_imagegen_plan";

    private final ImagegenPlanService redrawService;

    private final ProposalService proposalService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final CanonicalAssetReferenceCodec referenceCodec =
            new CanonicalAssetReferenceCodec();

    public SubmitImagegenPlanHandler(
            ImagegenPlanService redrawService,
            ProposalService proposalService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.redrawService = redrawService;
        this.proposalService = proposalService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ToolDescriptor submitImagegenPlanDescriptor() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("referenceKey", "prompt"),
                "properties", Map.of(
                        "referenceKey", Map.of("type", "string", "minLength", 1),
                        "prompt", Map.of("type", "string", "minLength", 1, "maxLength", 2000),
                        "note", Map.of("type", "string", "maxLength", 1024),
                        "params", Map.of("type", "object")));
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "proposalId", Map.of("type", "string"),
                        "payloadHash", Map.of("type", "string"),
                        "sourceCount", Map.of("type", "integer"),
                        "summary", Map.of("type", "string")));
        ToolHandler handler = this::handle;
        return new ToolDescriptor(
                TOOL_NAME,
                "提交一个 concrete IMAGE 的重绘 Proposal。",
                inputSchema,
                outputSchema,
                "submit_imagegen_plan 只接受一个 canonical IMAGE referenceKey。",
                true,
                handler,
                (descriptor, arguments) -> new ToolCallClassification(
                        true, true, TOOL_NAME, Map.of(), ToolResultPolicy.defaults()),
                (descriptor, arguments) -> validateInput(arguments),
                ToolResultPolicy.defaults());
    }

    private static void validateInput(Map<String, Object> arguments) {
        requiredText(arguments, "referenceKey");
        requiredText(arguments, "prompt");
        Object params = arguments.get("params");
        if (params != null && !(params instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("params 必须是 object");
        }
    }

    ToolHandlerOutput handle(ToolInvocation invocation) {
        try {
            Map<String, Object> arguments = invocation.arguments();
            String referenceKey = requiredText(arguments, "referenceKey");
            String prompt = requiredText(arguments, "prompt");
            Object paramsValue = arguments.get("params");
            if (paramsValue != null && !(paramsValue instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("params 必须是 object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = paramsValue == null
                    ? Map.of() : (Map<String, Object>) paramsValue;
            String note = arguments.get("note") instanceof String text ? text : null;
            ValidatedRedrawRequest validated = redrawService.validate(
                    new ImagegenPlanInputs(referenceKey, prompt, note, params),
                    invocation.conversationId());
            String canonicalKey = referenceCodec.serialize(validated.source());
            List<String> referenceKeys = List.of(canonicalKey);
            invocation.proposalPublicationAuthorizer().authorize(
                    "IMAGEGEN", referenceKeys, validated.payloadHash());
            ProposalView proposal = proposalService.publish(new PublishProposalCommand(
                    PendingProposalType.IMAGEGEN,
                    invocation.conversationId(),
                    validated.source().packageId(),
                    invocation.toolCallId(),
                    validated.canonicalPayload(),
                    validated.payloadHash(),
                    1,
                    referenceKeys,
                    clock.instant()));
            ProposalReadyEvent readyEvent = new ProposalReadyEvent(
                    proposal.proposalId(),
                    invocation.conversationId(),
                    "IMAGEGEN",
                    "生成图片",
                    "单图重绘方案已准备完成",
                    List.of("已选择 1 项素材"),
                    clock.instant());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("proposalId", proposal.proposalId());
            result.put("payloadHash", proposal.payloadHash());
            result.put("sourceCount", 1);
            result.put("summary", "已发布单图重绘 Proposal，等待用户确认");
            // trace 只持久化受控 metadata；显式携带 owner hash，供离线评估绑定确认决定。
            return new ToolHandlerOutput(
                    objectMapper.writeValueAsString(result),
                    Map.of(
                            "payloadHash", proposal.payloadHash(),
                            ProposalReadyEvent.METADATA_KEY, readyEvent));
        } catch (PixFlowException exception) {
            return error(exception.code().code(), exception.getMessage());
        } catch (Exception exception) {
            return error("IMAGEGEN_PROMPT_INVALID", exception.getMessage());
        }
    }

    private static String requiredText(Map<String, Object> arguments, String name) {
        if (arguments == null || !(arguments.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " 必须是非空字符串");
        }
        return value;
    }

    private ToolHandlerOutput error(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", code + ": " + Sanitizer.sanitizeMessage(message));
        try {
            return ToolHandlerOutput.of(objectMapper.writeValueAsString(error));
        } catch (Exception serializationFailure) {
            return ToolHandlerOutput.of("{\"error\":\"IMAGEGEN_PROMPT_INVALID: 内部错误\"}");
        }
    }
}
