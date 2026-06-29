package com.pixflow.module.dag.ir;

import java.util.List;
import java.util.Objects;

/**
 * 校验通过的 DAG 不可变结构。
 *
 * <p>校验器把 DagDocument 中所有不变量(结构/白名单/参数/边/无环/组支路规则/链首约束)都验证通过后,
 * 才允许进入 {@link com.pixflow.module.dag.expand.BranchExpander} 展开。
 *
 * <p>类型上区分 DagDocument 是为了"未校验对象不能进展开器"的编译期约束(与设计文档 §5 落实)。
 */
public record ValidatedDag(List<DagNode> nodes, List<DagEdge> edges, DagSchemaVersion schemaVersion) {
    public ValidatedDag {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
