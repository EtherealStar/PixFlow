package com.etherealstar.pixflow.module.dag;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DAG ↔ JSON 序列化编解码器（需求 12.6，Task_Manager 任务创建）。
 *
 * <p>任务创建时需将已校验通过的 {@link Dag} 序列化为 {@code dag_json} 持久化到 {@code process_task}
 * （需求 12.6）。本编解码器以确定性方式将 {@link Dag} 写出为 {@code {nodes:[{id,tool,params}], edges:[{from,to}]}}
 * 结构，并保证与 {@link com.etherealstar.pixflow.module.dag.validator.DagValidator#validateJson} 的解析互逆：
 * 对任意合法 DAG，{@code parse(write(dag)).equals(dag)} 恒成立（序列化往返一致，Property 35）。</p>
 *
 * <p>序列化保留节点声明顺序与边声明顺序，节点 {@code params} 以原始键值原样写出（{@code null} 视为空对象），
 * 从而使往返前后的 {@link Dag} 在 {@code equals} 语义下完全一致。</p>
 */
@Component
public class DagJsonCodec {

    private final ObjectMapper objectMapper;

    public DagJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 {@link Dag} 序列化为规范的 DAG JSON 字符串。
     *
     * @param dag 待序列化的 DAG（非空）
     * @return DAG JSON 字符串
     * @throws BusinessException 序列化失败时（INTERNAL_ERROR）
     */
    public String write(Dag dag) {
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_STRUCTURE_INVALID, "DAG 不可为空");
        }
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode nodes = root.putArray("nodes");
        for (DagNode node : dag.getNodes()) {
            ObjectNode nodeJson = nodes.addObject();
            nodeJson.put("id", node.getId());
            nodeJson.put("tool", node.getTool());
            Map<String, Object> params = node.getParams() == null
                    ? new LinkedHashMap<>() : node.getParams();
            nodeJson.set("params", objectMapper.valueToTree(params));
        }

        ArrayNode edges = root.putArray("edges");
        for (DagEdge edge : dag.getEdges()) {
            ObjectNode edgeJson = edges.addObject();
            edgeJson.put("from", edge.getFrom());
            edgeJson.put("to", edge.getTo());
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "DAG 序列化失败：" + e.getMessage());
        }
    }
}
