package com.pixflow.module.dag.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * DAG JSON → DagDocument 的浅解析器。
 *
 * <p>职责边界:仅做"合法 JSON object、含 nodes/edges 顶层键、节点 tool 字段存在、id 字段存在"
 * 的**结构层**解析。**深度校验**(白名单/参数 schema/边引用/无环)由
 * {@link com.pixflow.module.dag.validate.DagValidator} 负责。
 *
 * <p>工具层 {@code submit_image_plan} 入口只调本类做浅解析,把结构性合法判断下放给 dag handler
 * 做深度校验,符合 {@code harness/tools.md §十四} 的设计。
 */
public final class DagJsonReader {

    private final ObjectMapper objectMapper;

    public DagJsonReader() {
        this(new ObjectMapper());
    }

    public DagJsonReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析字节流为 DagDocument;输入非法抛 IllegalArgumentException。 */
    public DagDocument read(byte[] jsonBytes) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("DAG JSON 解析失败", e);
        }
        return readTree(root);
    }

    /** 与 {@link #read(byte[])} 等价,接受 String。 */
    public DagDocument read(String json) {
        return read(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 解析已有 JsonNode(便于解析器复用上游 Jackson 树)。 */
    public DagDocument readTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("DAG JSON 顶层必须是 object");
        }
        JsonNode nodesNode = root.get("nodes");
        JsonNode edgesNode = root.get("edges");
        if (nodesNode == null || !nodesNode.isArray()) {
            throw new IllegalArgumentException("DAG JSON 缺少 nodes 数组");
        }
        if (edgesNode == null || !edgesNode.isArray()) {
            throw new IllegalArgumentException("DAG JSON 缺少 edges 数组");
        }
        List<DagNode> nodes = new ArrayList<>(nodesNode.size());
        for (int i = 0; i < nodesNode.size(); i++) {
            nodes.add(parseNode(nodesNode.get(i), i));
        }
        List<DagEdge> edges = new ArrayList<>(edgesNode.size());
        for (int i = 0; i < edgesNode.size(); i++) {
            edges.add(parseEdge(edgesNode.get(i), i));
        }
        return new DagDocument(nodes, edges);
    }

    private DagNode parseNode(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("nodes[" + index + "] 必须是 object");
        }
        JsonNode idNode = node.get("id");
        JsonNode toolNode = node.get("tool");
        if (idNode == null || !idNode.isTextual()) {
            throw new IllegalArgumentException("nodes[" + index + "].id 缺失或非字符串");
        }
        if (toolNode == null || !toolNode.isTextual()) {
            throw new IllegalArgumentException("nodes[" + index + "].tool 缺失或非字符串");
        }
        PixelTool tool = PixelTool.fromWireName(toolNode.asText());
        if (tool == null) {
            throw new IllegalArgumentException(
                "nodes[" + index + "].tool='" + toolNode.asText() + "' 不在像素工具白名单内");
        }
        JsonNode paramsNode = node.get("params");
        Map<String, Object> params = parseParams(paramsNode, index);
        return new DagNode(idNode.asText(), tool, params);
    }

    private Map<String, Object> parseParams(JsonNode paramsNode, int nodeIndex) {
        if (paramsNode == null || paramsNode.isNull()) {
            return Map.of();
        }
        if (!paramsNode.isObject()) {
            throw new IllegalArgumentException("nodes[" + nodeIndex + "].params 必须是 object");
        }
        Map<String, Object> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = paramsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            result.put(e.getKey(), jsonToJava(e.getValue()));
        }
        return result;
    }

    private DagEdge parseEdge(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("edges[" + index + "] 必须是 object");
        }
        JsonNode from = node.get("from");
        JsonNode to = node.get("to");
        if (from == null || !from.isTextual()) {
            throw new IllegalArgumentException("edges[" + index + "].from 缺失或非字符串");
        }
        if (to == null || !to.isTextual()) {
            throw new IllegalArgumentException("edges[" + index + "].to 缺失或非字符串");
        }
        return new DagEdge(from.asText(), to.asText());
    }

    /** 把 JsonNode 转为 Java 原生类型(Map/List/String/Number/Boolean/null)。 */
    private Object jsonToJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> m = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                m.put(e.getKey(), jsonToJava(e.getValue()));
            }
            return m;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                list.add(jsonToJava(node.get(i)));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBigDecimal()) {
            return node.decimalValue();
        }
        return node.asText();
    }
}