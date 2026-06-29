package com.pixflow.module.dag.ir;

import java.util.Objects;

/**
 * DAG 边:source→sink 的连接关系(无向对,排序由拓扑算法决定)。
 */
public record DagEdge(String from, String to) {
    public DagEdge {
        Objects.requireNonNull(from, "edge.from");
        Objects.requireNonNull(to, "edge.to");
    }
}
