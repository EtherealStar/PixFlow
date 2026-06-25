package com.etherealstar.pixflow.module.dag.parser;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.ai.DagPromptManager;
import com.etherealstar.pixflow.infra.ai.LlmClient;
import com.etherealstar.pixflow.infra.ai.LlmException;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.schema.ToolParamSchema;
import com.etherealstar.pixflow.module.dag.schema.ToolSchemaRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DAG 解析模块（DAG_Parser）。
 *
 * <p>将电商运营人员的自然语言指令解析为工具编排 DAG，并在必填参数缺失时向用户追问（需求 6.1–6.6）。
 * 流程：
 * <ol>
 *   <li>通过 {@link DagPromptManager} 构造固定工具白名单与参数 schema 的提示词，调用 {@link LlmClient}
 *       将自然语言转为 DAG JSON（需求 6.1）。</li>
 *   <li>解析 LLM 输出为 {@link Dag}；若无法解析为合法 DAG JSON，则不生成任务并以
 *       {@link ErrorCode#DAG_PARSE_FAILED} 拒绝（需求 6.6）。</li>
 *   <li>经 {@link DagNormalizer} 做确定性规范化排序，固定 {@code convert_format} 与 {@code compress}
 *       共现时的先后顺序（需求 6.4）。</li>
 *   <li>逐节点依据 {@link ToolSchemaRegistry} 检查必填参数：存在缺失则逐项列出 {@link MissingParam}
 *       并追问，{@code needConfirm=true}、不填充、不生成任务（需求 6.2、6.5）；
 *       全部齐备则返回 {@code dagPreview} 供确认（需求 6.3）。</li>
 * </ol>
 *
 * <p>本类不直接生成可执行任务——任务仅在用户确认（{@code /confirm}）后创建。LLM 调用被隔离在
 * {@link LlmClient} 接口之后，便于测试以内存替身替代真实模型。</p>
 */
@Service
public class DagParser {

    private static final Logger log = LoggerFactory.getLogger(DagParser.class);

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final LlmClient llmClient;
    private final DagPromptManager promptManager;
    private final DagNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public DagParser(LlmClient llmClient,
                     DagPromptManager promptManager,
                     DagNormalizer normalizer,
                     ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.promptManager = promptManager;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 将自然语言指令解析为 DAG 并判定追问/预览终态。
     *
     * @param instruction 用户的自然语言处理指令
     * @return 解析结果（缺参追问或预览待确认）
     * @throws BusinessException 当指令为空、LLM 调用失败或输出无法解析为合法 DAG（{@link ErrorCode#DAG_PARSE_FAILED}）时
     */
    public DagParseResult parse(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "指令内容不能为空");
        }

        String systemPrompt = promptManager.buildSystemPrompt();
        String userPrompt = promptManager.buildUserPrompt(instruction);

        String rawOutput;
        try {
            rawOutput = llmClient.complete(systemPrompt, userPrompt);
        } catch (LlmException e) {
            log.warn("LLM 调用失败，无法解析指令: {}", e.getMessage());
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "调用模型解析指令失败: " + e.getMessage());
        }

        Dag dag = toDag(rawOutput);
        dag = normalizer.normalize(dag);

        List<MissingParam> missing = detectMissingParams(dag);
        if (!missing.isEmpty()) {
            return DagParseResult.missing(missing, buildMissingReply(missing));
        }
        return DagParseResult.preview(dag, buildPreviewReply(dag));
    }

    // ---- LLM 输出 → Dag ----------------------------------------------------

    private Dag toDag(String rawOutput) {
        String json = stripCodeFences(rawOutput);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "模型输出不是合法 JSON，无法解析为 DAG");
        }
        if (root == null || !root.isObject()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "模型输出缺少合法的 DAG 对象结构");
        }

        JsonNode nodesNode = root.get("nodes");
        JsonNode edgesNode = root.get("edges");
        if (nodesNode == null || !nodesNode.isArray() || edgesNode == null || !edgesNode.isArray()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "模型输出缺少 nodes 或 edges 数组");
        }

        List<DagNode> nodes = new ArrayList<>();
        for (JsonNode nodeJson : nodesNode) {
            nodes.add(toNode(nodeJson));
        }

        List<DagEdge> edges = new ArrayList<>();
        for (JsonNode edgeJson : edgesNode) {
            edges.add(toEdge(edgeJson));
        }

        return new Dag(nodes, edges);
    }

    private DagNode toNode(JsonNode nodeJson) {
        if (nodeJson == null || !nodeJson.isObject()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "节点结构非法");
        }
        String id = textOrNull(nodeJson.get("id"));
        String tool = textOrNull(nodeJson.get("tool"));
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "存在缺少 id 的节点");
        }
        if (tool == null || tool.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "节点 " + id + " 缺少 tool");
        }
        // 工具必须在白名单内（需求 6.1）：否则视为无法解析为合法 DAG
        if (!ToolSchemaRegistry.isWhitelisted(tool)) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "节点 " + id + " 使用了非白名单工具: " + tool);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = nodeJson.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) {
                throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "节点 " + id + " 的 params 必须为对象");
            }
            params = objectMapper.convertValue(paramsNode, PARAMS_TYPE);
        }
        return new DagNode(id, tool, params);
    }

    private DagEdge toEdge(JsonNode edgeJson) {
        if (edgeJson == null || !edgeJson.isObject()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "边结构非法");
        }
        String from = textOrNull(edgeJson.get("from"));
        String to = textOrNull(edgeJson.get("to"));
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_PARSE_FAILED, "边缺少 from 或 to");
        }
        return new DagEdge(from, to);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    private String stripCodeFences(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            // 去除起始 ``` 或 ```json 标记
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    // ---- 必填参数检查（不填充，仅追问，需求 6.2、6.5）------------------------

    private List<MissingParam> detectMissingParams(Dag dag) {
        List<MissingParam> missing = new ArrayList<>();
        for (DagNode node : dag.getNodes()) {
            ToolParamSchema schema = ToolSchemaRegistry.findByToolName(node.getTool())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.DAG_PARSE_FAILED, "节点 " + node.getId() + " 使用了非白名单工具"));
            Map<String, Object> params = node.getParams();

            // 1. 单个必填参数缺失
            for (String required : schema.requiredParamNames()) {
                if (!isPresent(params, required)) {
                    missing.add(new MissingParam(node.getId(), required));
                }
            }
            // 2. 二选一（one-of）组：组内成员均未提供则追问
            for (Set<String> group : schema.oneOfGroups()) {
                boolean anyPresent = group.stream().anyMatch(name -> isPresent(params, name));
                if (!anyPresent) {
                    StringJoiner joiner = new StringJoiner("|");
                    group.forEach(joiner::add);
                    missing.add(new MissingParam(node.getId(), joiner.toString()));
                }
            }
        }
        return missing;
    }

    private boolean isPresent(Map<String, Object> params, String name) {
        if (params == null || !params.containsKey(name)) {
            return false;
        }
        Object value = params.get(name);
        if (value == null) {
            return false;
        }
        return !(value instanceof String s) || !s.isBlank();
    }

    // ---- 面向用户的回复文案 ------------------------------------------------

    private String buildMissingReply(List<MissingParam> missing) {
        StringJoiner joiner = new StringJoiner("；");
        for (MissingParam mp : missing) {
            joiner.add("节点 " + mp.nodeId() + " 的「" + mp.param() + "」");
        }
        return "处理流程已初步解析，但以下必填参数尚未提供，请补充后再确认执行：" + joiner + "。";
    }

    private String buildPreviewReply(Dag dag) {
        return "已将指令解析为包含 " + dag.getNodes().size() + " 个处理步骤的流程，请确认预览后执行。";
    }
}
