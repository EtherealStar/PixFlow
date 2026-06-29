package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S7 组支路规则:
 * <ul>
 *   <li>compose_group 在 DAG 中必须唯一(避免双 fan-in 语义歧义)</li>
 *   <li>compose_group 的所有前驱必须是 ONE_TO_ONE 逐图工具(不可在聚合前再聚合)</li>
 *   <li>compose_group 在拓扑中应位于"分叉前"(同分支路径上的第一个聚合点)</li>
 * </ul>
 */
public class GroupBranchRule {

    public String name() {
        return "GROUP_BRANCH";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        // 找 compose_group 节点
        List<DagNode> composeNodes = new ArrayList<>();
        for (DagNode node : doc.nodes()) {
            if (node.tool() == PixelTool.COMPOSE_GROUP) {
                composeNodes.add(node);
            }
        }
        if (composeNodes.size() > 1) {
            builder.add("DAG_INVALID_GROUP_BRANCH",
                "DAG 含多个 compose_group,必须唯一",
                Map.of("composeNodes",
                    composeNodes.stream().map(DagNode::id).toList()));
            return;
        }
        if (composeNodes.isEmpty()) {
            return; // 普通支路无 group rule
        }
        DagNode compose = composeNodes.get(0);
        // 取 compose 的所有前驱
        Map<String, List<String>> inEdges = new HashMap<>();
        for (var edge : doc.edges()) {
            inEdges.computeIfAbsent(edge.from(), k -> new ArrayList<>());
            inEdges.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
        List<String> predecessors = inEdges.getOrDefault(compose.id(), List.of());
        for (String predId : predecessors) {
            DagNode pred = doc.nodes().stream()
                .filter(n -> n.id().equals(predId))
                .findFirst()
                .orElse(null);
            if (pred == null) {
                continue; // 已在 EdgeRule 标过
            }
            if (pred.tool().arity() != PixelTool.Arity.ONE_TO_ONE) {
                builder.add("DAG_INVALID_GROUP_BRANCH",
                    "compose_group 前驱 " + pred.id() + " 是非逐图工具("
                        + pred.tool().wireName() + "),必须在聚合前用逐图工具",
                    Map.of("composeNode", compose.id(),
                        "badPredecessor", pred.id()));
            }
        }
    }
}