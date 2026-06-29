package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagEdge;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S5 边引用完整性:每条边的 from/to 必须指向存在的节点 id。
 *
 * <p>没有此规则,后续拓扑排序会因找不到节点而崩溃。
 */
public class EdgeRule {

    public String name() {
        return "EDGE";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        Set<String> nodeIds = new HashSet<>();
        for (var node : doc.nodes()) {
            nodeIds.add(node.id());
        }
        List<String> dangling = new ArrayList<>();
        for (int i = 0; i < doc.edges().size(); i++) {
            DagEdge edge = doc.edges().get(i);
            if (!nodeIds.contains(edge.from())) {
                dangling.add("edges[" + i + "].from=" + edge.from());
            }
            if (!nodeIds.contains(edge.to())) {
                dangling.add("edges[" + i + "].to=" + edge.to());
            }
        }
        if (!dangling.isEmpty()) {
            builder.add("DAG_INVALID_STRUCTURE",
                "边引用不存在的节点: " + String.join(", ", dangling),
                java.util.Map.of("dangling", dangling));
        }
    }
}