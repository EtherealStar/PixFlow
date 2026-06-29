package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagEdge;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S6 无环规则:Kahn 算法判定拓扑可排序性;存在环 → DAG_HAS_CYCLE。
 *
 * <p>使用 Kahn 而非 DFS 避免爆栈;同时输出 cycleNodes 详情便于排错。
 */
public class AcyclicRule {

    public String name() {
        return "ACYCLIC";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        if (doc.nodes().isEmpty()) {
            return;
        }
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (var node : doc.nodes()) {
            inDegree.putIfAbsent(node.id(), 0);
            adjacency.computeIfAbsent(node.id(), k -> new java.util.ArrayList<>());
        }
        for (DagEdge edge : doc.edges()) {
            // 防御性:即便 EdgeRule 没拦住,拓扑排序也应对 dangling 节点初始化
            inDegree.putIfAbsent(edge.to(), 0);
            inDegree.putIfAbsent(edge.from(), 0);
            adjacency.computeIfAbsent(edge.from(), k -> new java.util.ArrayList<>()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.offer(e.getKey());
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            for (String next : adjacency.getOrDefault(cur, List.of())) {
                int degree = inDegree.merge(next, -1, Integer::sum);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }
        if (processed < doc.nodes().size()) {
            Set<String> remaining = new HashSet<>(inDegree.keySet());
            // 已访问的节点 inDegree 必已归 0(若未归 0 必是环上节点);故取所有入度>0 的节点即可
            List<String> cycleNodes = new ArrayList<>();
            for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
                if (e.getValue() > 0) {
                    cycleNodes.add(e.getKey());
                }
            }
            remaining.retainAll(cycleNodes);
            builder.add("DAG_HAS_CYCLE",
                "DAG 存在环,无法拓扑排序",
                Map.of("cycleNodes", cycleNodes));
        }
    }
}