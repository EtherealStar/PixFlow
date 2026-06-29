package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.Map;

/**
 * S2 节点数规则:1 ≤ |nodes| ≤ maxNodes(默认 50,见 dag.md §5)。
 *
 * <p>防超大图编译,保护下游工具链无界计算。
 */
public class NodeLimitRule {

    private final int maxNodes;
    private final int minNodes;

    public NodeLimitRule(int maxNodes, int minNodes) {
        this.maxNodes = maxNodes;
        this.minNodes = minNodes;
    }

    public String name() {
        return "NODE_LIMIT";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        int n = doc.nodes().size();
        if (n < minNodes) {
            builder.add("DAG_NODE_LIMIT_EXCEEDED",
                "节点数 " + n + " 低于下限 " + minNodes,
                Map.of("actual", n, "min", minNodes));
        }
        if (n > maxNodes) {
            builder.add("DAG_NODE_LIMIT_EXCEEDED",
                "节点数 " + n + " 超过上限 " + maxNodes,
                Map.of("actual", n, "max", maxNodes));
        }
    }
}