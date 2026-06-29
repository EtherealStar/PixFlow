package com.pixflow.module.imagegen.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.tools.ToolCallClassification;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInputValidator;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolResultPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * submit_imagegen_plan 工具 handler(对齐 imagegen.md §五 / §七)。
 *
 * <p>职责:
 * <ul>
 *   <li>浅层校验(source_image_ids 非空 / prompt 非空字符串)</li>
 *   <li>深校验(经 {@link ImagegenPlanValidator},覆盖 10 条 ImagegenErrorCode 中 5 条)</li>
 *   <li>调 {@link ImagegenPlanService#enqueue}(入队幂等)</li>
 *   <li>返回 {@code {planId, summary}} 摘要</li>
 * </ul>
 *
 * <p>双重拦截:
 * <ul>
 *   <li>{@link ImagegenPlanDescriptor} 在子 Agent 上下文中由 harness/tools 过滤掉(schema 层)</li>
 *   <li>{@code ToolDescriptor.readOnlyHint=true} 让 permission 阶段 A 对子 Agent 短路拒绝</li>
 * </ul>
 *
 * <p>工具 schema 不含 token 字段(对齐 imagegen.md §七 / permission.md §5.1)。
 */
@Component
public class ImagegenPlanToolHandler {

    public static final String TOOL_NAME = "submit_imagegen_plan";

    private final ImagegenPlanService service;
    private final ObjectMapper objectMapper;

    @Autowired
    public ImagegenPlanToolHandler(ImagegenPlanService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** 描述符 + handler 一起暴露(对齐 dag 的 SubmitImagePlanHandler 同款写法)。 */
    @Bean
    public ToolDescriptor submitImagegenPlanDescriptor() {
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("source_image_ids", "prompt"),
            "properties", Map.of(
                "source_image_ids", Map.of(
                    "type", "array",
                    "minItems", 1,
                    "items", Map.of("type", "string", "minLength", 1)
                ),
                "prompt", Map.of(
                    "type", "string",
                    "minLength", 1,
                    "maxLength", 2000
                ),
                "note", Map.of("type", "string", "maxLength", 1024),
                "params", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "properties", Map.of(
                        "style", Map.of("type", "string"),
                        "strength", Map.of("type", "number"),
                        "negative_prompt", Map.of("type", "string"),
                        "seed", Map.of("type", "integer")
                    )
                )
            )
        );
        Map<String, Object> outputSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "planId", Map.of("type", "string"),
                "payloadHash", Map.of("type", "string"),
                "sourceCount", Map.of("type", "integer"),
                "summary", Map.of("type", "string")
            )
        );

        ToolHandler handler = this::handle;
        return new ToolDescriptor(
            TOOL_NAME,
            "提交生图提案到确认队列(零副作用、零令牌)。"
                + "用户在前端带外确认后,确认 REST 边界才会触发真实重绘执行。",
            inputSchema,
            outputSchema,
            "submit_imagegen_plan 用于提交源图重绘提案。"
                + "source_image_ids 必填(至少 1 张),prompt 必填;"
                + "校验失败返回结构化 tool error;幂等(同 toolCallId 不重复入队)。"
                + "本工具对子 Agent 不可见,handler 标 readOnly 触发 permission 阶段 A 短路。",
            true, // readOnlyHint = true:子 Agent 调 → permission 阶段 A 拒(SUBAGENT_FORBIDDEN_ACTION)
            handler,
            (descriptor, arguments) -> new ToolCallClassification(
                true, // readOnly
                true, // concurrencySafe
                TOOL_NAME,
                Map.of(),
                ToolResultPolicy.defaults()),
            ToolInputValidator.noop(),
            ToolResultPolicy.defaults()
        );
    }

    /** handler 方法本体(对齐 imagegen.md §5.1 / §十五)。 */
    ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        if (args == null) {
            return error("IMAGEGEN_PROMPT_INVALID", "入参不能为空");
        }
        Object sourceIdsObj = args.get("source_image_ids");
        if (!(sourceIdsObj instanceof List<?> list) || list.isEmpty()) {
            return error("IMAGEGEN_SOURCE_IMAGE_NOT_FOUND", "source_image_ids 必须是非空字符串数组");
        }
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                return error("IMAGEGEN_SOURCE_IMAGE_NOT_FOUND", "source_image_ids 含非字符串或空白项");
            }
        }
        Object promptObj = args.get("prompt");
        if (!(promptObj instanceof String prompt) || prompt.isBlank()) {
            return error("IMAGEGEN_PROMPT_INVALID", "prompt 必须是非空字符串");
        }
        String note = args.get("note") == null ? null : args.get("note").toString();
        Object paramsObj = args.get("params");
        if (paramsObj != null && !(paramsObj instanceof Map)) {
            return error("IMAGEGEN_PROMPT_INVALID", "params 必须是 object 或缺省");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = paramsObj == null ? Map.of() : (Map<String, Object>) paramsObj;

        // 包装入参 record
        ImagegenPlanInputs inputs = new ImagegenPlanInputs(
            list.stream().map(o -> (String) o).toList(),
            prompt,
            note,
            params);

        // packageId 从 invocation.metadata / ToolInvocation 上下文取;
        // 本期对齐 dag 路径,先要求 metadata.packageId 必填。
        String packageId = invocation.metadata() == null ? null
            : (String) invocation.metadata().get("packageId");
        if (packageId == null || packageId.isBlank()) {
            return error("IMAGEGEN_SOURCE_NOT_IN_PACKAGE", "缺少 packageId(由会话上下文给定)");
        }

        try {
            String planId = service.enqueue(inputs,
                invocation.toolCallId(),
                invocation.conversationId(),
                packageId);
            // 摘要:payloadHash 与入队时一致(让 UI 能直接展示)
            String payloadHash = service.payloadHashFor(servicePayloadHash(inputs, packageId,
                invocation.conversationId()));
            String summary = "已入队 " + list.size() + " 张源图重绘提案,等待用户确认";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planId", planId);
            result.put("payloadHash", payloadHash);
            result.put("sourceCount", list.size());
            result.put("summary", summary);
            return ToolHandlerOutput.of(objectMapper.writeValueAsString(result));
        } catch (PixFlowException pe) {
            return error(pe.code().code(), pe.getMessage());
        } catch (Exception e) {
            return error("IMAGEGEN_PROMPT_INVALID", Sanitizer.sanitizeMessage(e.getMessage()));
        }
    }

    /** 错误返回结构化 tool error(对齐 harness/tools §十五)。 */
    private ToolHandlerOutput error(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", code + ": " + Sanitizer.sanitizeMessage(message == null ? "" : message));
        try {
            return ToolHandlerOutput.of(objectMapper.writeValueAsString(err));
        } catch (Exception e) {
            return ToolHandlerOutput.of("{\"error\":\"" + code + ": 内部错误\"}");
        }
    }

    /** 计算 payloadHash(供 handler 返回字段直接透出;与 confirm 时重算口径一致)。 */
    private ImagegenPlan servicePayloadHash(ImagegenPlanInputs inputs, String packageId, String conversationId) {
        java.util.List<String> sortedIds = inputs.source_image_ids().stream()
            .distinct().sorted().toList();
        String trimmed = inputs.prompt().trim();
        java.util.Map<String, Object> normalizedParams = new java.util.TreeMap<>();
        if (inputs.params() != null) {
            // 仅取 hasher 关注的 4 个白名单键;白名单外键不入 hash(校验由 validator 兜底)
            for (String k : List.of("style", "strength", "negative_prompt", "seed")) {
                if (inputs.params().containsKey(k) && inputs.params().get(k) != null) {
                    normalizedParams.put(k, inputs.params().get(k));
                }
            }
        }
        return new ImagegenPlan(sortedIds, trimmed, normalizedParams, inputs.note(),
            conversationId, packageId);
    }
}