package com.pixflow.module.dag.propose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.tools.ToolCallClassification;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInputValidator;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolResultPolicy;
import com.pixflow.module.dag.ir.DagDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * SubmitImagePlanHandler:实现 {@link ToolHandler} 接口,贡献给 harness/tools。
 *
 * <p>工具入参:`{dag: {nodes:[...], edges:[...]}, note?: string}`。
 * 执行步骤:
 * <ol>
 *   <li>dag 浅层校验(合法 JSON object、含 nodes/edges)</li>
 *   <li>PendingPlanService.parseDocument → DagDocument</li>
 *   <li>DagValidator 深度校验</li>
 *   <li>PendingPlanService.enqueue(幂等)</li>
 *   <li>返回 planId + summary</li>
 * </ol>
 *
 * <p>Handler 不执行像素处理、不携带确认令牌;真实执行由用户在带外确认 REST 边界触发。
 */
@Component
public class SubmitImagePlanHandler {

    public static final String TOOL_NAME = "submit_image_plan";

    private final PendingPlanService pendingPlanService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SubmitImagePlanHandler(PendingPlanService pendingPlanService,
                                  ObjectMapper objectMapper) {
        this.pendingPlanService = pendingPlanService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ToolDescriptor submitImagePlanDescriptor() {
        Map<String, Object> dagSchema = Map.of(
            "type", "object",
            "required", java.util.List.of("nodes", "edges"),
            "properties", Map.of(
                "nodes", Map.of("type", "array"),
                "edges", Map.of("type", "array")
            )
        );
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", java.util.List.of("dag"),
            "properties", Map.of(
                "dag", dagSchema,
                "note", Map.of("type", "string", "maxLength", 1024)
            )
        );
        Map<String, Object> outputSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "planId", Map.of("type", "integer"),
                "payloadHash", Map.of("type", "string"),
                "status", Map.of("type", "string"),
                "summary", Map.of("type", "string")
            )
        );
        ToolHandler handler = this::handle;
        return new ToolDescriptor(
            TOOL_NAME,
            "提交 DAG 提案到确认队列(不执行像素处理)。"
                + "用户在前端带外确认后,确认 REST 边界才会触发真实执行。",
            inputSchema,
            outputSchema,
            "submit_image_plan 用于提交图片处理 DAG 提案。dag 字段含 nodes/edges;"
                + "校验失败返回结构化 tool error;幂等(同 toolCallId 不重复入队)。",
            false, // 非只读
            handler,
            (descriptor, arguments) -> new ToolCallClassification(false, true,
                "submit_image_plan", Map.of(), ToolResultPolicy.defaults()),
            ToolInputValidator.noop(),
            ToolResultPolicy.defaults()
        );
    }

    ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        Object dagObj = args == null ? null : args.get("dag");
        if (dagObj == null) {
            return ToolHandlerOutput.of(
                "{\"error\":\"DAG_INVALID_STRUCTURE: 缺少 dag 字段\"}");
        }
        String dagJson;
        try {
            dagJson = objectMapper.writeValueAsString(dagObj);
        } catch (Exception e) {
            return ToolHandlerOutput.of(
                "{\"error\":\"DAG_INVALID_STRUCTURE: dag JSON 序列化失败\"}");
        }
        // 浅层校验(tools 层只验 JSON object + nodes/edges)
        try {
            Map<?, ?> dagMap = objectMapper.readValue(dagJson, Map.class);
            if (!dagMap.containsKey("nodes") || !dagMap.containsKey("edges")) {
                return ToolHandlerOutput.of(
                    "{\"error\":\"DAG_INVALID_STRUCTURE: 缺少 nodes/edges 顶层键\"}");
            }
        } catch (Exception e) {
            return ToolHandlerOutput.of(
                "{\"error\":\"DAG_INVALID_STRUCTURE: dag 不是合法 JSON object\"}");
        }
        String note = args.get("note") == null ? null : args.get("note").toString();
        try {
            DagDocument doc = pendingPlanService.parseDocument(dagJson);
            PendingPlan plan = pendingPlanService.enqueue(
                invocation.toolCallId(),
                invocation.conversationId(),
                doc,
                note
            );
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planId", plan.getId());
            result.put("payloadHash", plan.getPayloadHash());
            result.put("status", plan.getStatus().name());
            result.put("summary", "已入队,等待用户确认");
            return ToolHandlerOutput.of(objectMapper.writeValueAsString(result));
        } catch (PixFlowException pe) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", pe.code().code() + ": "
                + Sanitizer.sanitizeMessage(pe.getMessage()));
            err.put("category", pe.category().name());
            try {
                return ToolHandlerOutput.of(objectMapper.writeValueAsString(err));
            } catch (Exception e) {
                return ToolHandlerOutput.of(
                    "{\"error\":\"DAG_INVALID_STRUCTURE: 内部错误\"}");
            }
        } catch (Exception e) {
            return ToolHandlerOutput.of(
                "{\"error\":\"DAG_INVALID_STRUCTURE: " + Sanitizer.sanitizeMessage(e.getMessage())
                    + "\"}");
        }
    }
}