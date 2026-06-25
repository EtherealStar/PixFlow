package com.etherealstar.pixflow.module.dag.validator;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.engine.TopologicalSorter;
import com.etherealstar.pixflow.module.dag.schema.DagValidationProperties;
import com.etherealstar.pixflow.module.dag.schema.ParamValidationResult;
import com.etherealstar.pixflow.module.dag.schema.ToolParamSchema;
import com.etherealstar.pixflow.module.dag.schema.ToolSchemaRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * DAG 校验器（DAG_Validator，需求 7.1–7.8、设计「DAG_Validator」节）。
 *
 * <p>在 {@code /confirm} 时于服务端独立、严格地校验 DAG，不信任前端回传结构（需求 7.1）。
 * 不可信的 LLM 生成结构或前端回传结构均在此处被拦截，避免破坏执行引擎。</p>
 *
 * <p>校验顺序固定，遇首个违例即以对应 {@link ErrorCode} 拒绝（fail-fast）：
 * <ol>
 *   <li>结构校验：JSON 可解析、含 {@code nodes} 与 {@code edges} 数组、各节点含 {@code id}
 *       （{@link ErrorCode#DAG_STRUCTURE_INVALID}，需求 7.2）；</li>
 *   <li>节点数校验：{@code 1 ≤ count ≤ maxNodeCount}（默认 50）
 *       （{@link ErrorCode#DAG_NODE_COUNT_INVALID}，需求 7.6）；</li>
 *   <li>工具白名单校验：每个节点 {@code tool} 须在白名单内
 *       （{@link ErrorCode#DAG_INVALID_TOOL}，需求 7.4）；</li>
 *   <li>参数 schema 校验：每个节点 {@code params} 须满足对应工具 schema，错误含节点 {@code id}
 *       （{@link ErrorCode#DAG_PARAM_INVALID}，需求 7.5）；</li>
 *   <li>边引用校验：每条边的 {@code from}/{@code to} 须指向存在的节点
 *       （{@link ErrorCode#DAG_EDGE_INVALID}，需求 7.7）；</li>
 *   <li>无环校验：DAG 可拓扑排序（{@link ErrorCode#DAG_CYCLE_DETECTED}，需求 7.3）。</li>
 * </ol>
 *
 * <p>全部通过后放行 DAG_Engine 执行（需求 7.8）。</p>
 *
 * <p>注意：本校验器在解析阶段对工具名保持宽松（不在解析时拒绝非白名单工具），
 * 以便将非白名单工具作为 {@link ErrorCode#DAG_INVALID_TOOL} 而非结构错误返回。</p>
 */
@Service
public class DagValidator {

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final TopologicalSorter topologicalSorter;
    private final DagValidationProperties properties;

    public DagValidator(ObjectMapper objectMapper,
                        TopologicalSorter topologicalSorter,
                        DagValidationProperties properties) {
        this.objectMapper = objectMapper;
        this.topologicalSorter = topologicalSorter;
        this.properties = properties;
    }

    /**
     * 校验 DAG JSON 字符串：先做结构解析（需求 7.2），再执行后续全部校验。
     *
     * @param dagJson 待校验的 DAG JSON
     * @return 解析并通过全部校验的 {@link Dag}（供 DAG_Engine 使用）
     * @throws BusinessException 任一校验失败时，携带对应 {@link ErrorCode} 与上下文
     */
    public Dag validateJson(String dagJson) {
        Dag dag = parseStructure(dagJson);
        validate(dag);
        return dag;
    }

    /**
     * 校验已解析的 {@link Dag}：执行节点数、工具白名单、参数 schema、边引用与无环校验
     * （需求 7.6、7.4、7.5、7.7、7.3）。
     *
     * @param dag 待校验的 DAG（非空，且应已具备结构）
     * @throws BusinessException 任一校验失败时
     */
    public void validate(Dag dag) {
        if (dag == null || dag.getNodes() == null || dag.getEdges() == null) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG 缺少 nodes 或 edges");
        }

        validateNodeCount(dag);
        validateToolWhitelist(dag);
        validateParams(dag);
        validateEdgeReferences(dag);
        validateAcyclic(dag);
    }

    // ---- 1. 结构解析（需求 7.2）-------------------------------------------

    private Dag parseStructure(String dagJson) {
        if (dagJson == null || dagJson.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG JSON 为空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(dagJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG JSON 无法解析");
        }
        if (root == null || !root.isObject()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG JSON 顶层须为对象");
        }

        JsonNode nodesNode = root.get("nodes");
        JsonNode edgesNode = root.get("edges");
        if (nodesNode == null || !nodesNode.isArray() || edgesNode == null || !edgesNode.isArray()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG JSON 缺少 nodes 或 edges 数组");
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
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "节点结构非法");
        }
        String id = textOrNull(nodeJson.get("id"));
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "存在缺少 id 的节点");
        }
        // tool 在此保持宽松：缺失或非白名单留待工具白名单校验阶段处理（需求 7.4）
        String tool = textOrNull(nodeJson.get("tool"));

        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = nodeJson.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) {
                throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "节点 " + id + " 的 params 必须为对象");
            }
            params = objectMapper.convertValue(paramsNode, PARAMS_TYPE);
        }
        return new DagNode(id, tool, params);
    }

    private DagEdge toEdge(JsonNode edgeJson) {
        if (edgeJson == null || !edgeJson.isObject()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "边结构非法");
        }
        String from = textOrNull(edgeJson.get("from"));
        String to = textOrNull(edgeJson.get("to"));
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "边缺少 from 或 to");
        }
        return new DagEdge(from, to);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    // ---- 2. 节点数校验（需求 7.6）-----------------------------------------

    private void validateNodeCount(Dag dag) {
        int count = dag.getNodes().size();
        int max = properties.getMaxNodeCount();
        if (count < 1 || count > max) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("nodeCount", count);
            details.put("min", 1);
            details.put("max", max);
            throw new BusinessException(ErrorCode.DAG_NODE_COUNT_INVALID,
                    "DAG 节点数 " + count + " 非法，须在 [1, " + max + "] 范围内", details);
        }
    }

    // ---- 3. 工具白名单校验（需求 7.4）-------------------------------------

    private void validateToolWhitelist(Dag dag) {
        for (DagNode node : dag.getNodes()) {
            if (!ToolSchemaRegistry.isWhitelisted(node.getTool())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("nodeId", node.getId());
                details.put("tool", node.getTool());
                throw new BusinessException(ErrorCode.DAG_INVALID_TOOL,
                        "节点 " + node.getId() + " 使用了非白名单工具: " + node.getTool(), details);
            }
        }
    }

    // ---- 4. 参数 schema 校验，错误含节点 id（需求 7.5）---------------------

    private void validateParams(Dag dag) {
        for (DagNode node : dag.getNodes()) {
            ToolParamSchema schema = ToolSchemaRegistry.findByToolName(node.getTool())
                    .orElseThrow(() -> new BusinessException(ErrorCode.DAG_INVALID_TOOL,
                            "节点 " + node.getId() + " 使用了非白名单工具"));
            ParamValidationResult result = schema.validate(node.getParams());
            if (!result.valid()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("nodeId", node.getId());
                details.put("errors", result.errors());
                throw new BusinessException(ErrorCode.DAG_PARAM_INVALID,
                        "节点 " + node.getId() + " 参数校验失败: " + String.join("; ", result.errors()), details);
            }
        }
    }

    // ---- 5. 边引用校验（需求 7.7）-----------------------------------------

    private void validateEdgeReferences(Dag dag) {
        for (DagEdge edge : dag.getEdges()) {
            if (!dag.containsNode(edge.getFrom()) || !dag.containsNode(edge.getTo())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("from", edge.getFrom());
                details.put("to", edge.getTo());
                throw new BusinessException(ErrorCode.DAG_EDGE_INVALID,
                        "边 " + edge.getFrom() + " -> " + edge.getTo() + " 引用了不存在的节点", details);
            }
        }
    }

    // ---- 6. 无环校验（需求 7.3）-------------------------------------------

    private void validateAcyclic(Dag dag) {
        if (topologicalSorter.hasCycle(dag)) {
            throw new BusinessException(ErrorCode.DAG_CYCLE_DETECTED, "DAG 中检测到环，无法执行");
        }
    }
}
