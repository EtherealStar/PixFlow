package com.pixflow.module.dag.ir;

import java.util.List;
import java.util.Objects;

/**
 * 浅解析的 DAG 文档:来自 Agent 的原始 JSON 经 {@link DagJsonReader} 解析。
 *
 * <p>此处**未校验**:节点 tool 可能为白名单外、边可能引用不存在的 id、结构可能含环。
 * 校验由 {@link com.pixflow.module.dag.validate.DagValidator} 负责,产出 ValidatedDag。
 */
public record DagDocument(List<DagNode> nodes, List<DagEdge> edges) {
    public DagDocument {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
