package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagEdge;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S8 链首约束:remove_bg 必须是该路径上"第一个非 source 节点"(普通支路整条链第一个;
 * 组支路 perMemberOps 第一个)。
 *
 * <p>理由(对齐 dag.md §5 与设计决策):先 resize 再抠图 = 把整张图发送第三方抠图服务,
 * 再让 image 二次处理 = 资源浪费。约束在 DagValidator 阶段拒绝,保证下游不会出现这种链。
 *
 * <p>算法:对每条 source→sink 路径,找到第一个 remove_bg;若它不是路径的第一个非 source 节点,
 * 则报告违规。
 */
public class OpOrderRule {

    public String name() {
        return "OP_ORDER";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        if (doc.nodes().isEmpty()) {
            return;
        }
        // 找 source 节点(无前驱)与 sink 节点(无后继)
        Map<String, Integer> outDegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, List<String>> predecessors = new HashMap<>();
        for (var node : doc.nodes()) {
            outDegree.putIfAbsent(node.id(), 0);
            successors.computeIfAbsent(node.id(), k -> new ArrayList<>());
            predecessors.computeIfAbsent(node.id(), k -> new ArrayList<>());
        }
        for (DagEdge edge : doc.edges()) {
            // 防御性:即使 EdgeRule 漏过 dangling,这里也要做空集合兜底
            outDegree.putIfAbsent(edge.from(), 0);
            outDegree.putIfAbsent(edge.to(), 0);
            successors.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            predecessors.computeIfAbsent(edge.from(), k -> new ArrayList<>());
            predecessors.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
        List<String> sources = new ArrayList<>();
        for (var node : doc.nodes()) {
            if (predecessors.get(node.id()).isEmpty()) {
                sources.add(node.id());
            }
        }
        if (sources.isEmpty()) {
            return; // 无 source(应该被 AcyclicRule 抓到)
        }
        // 对每个 source 做 DFS,枚举所有 source→sink 路径
        for (String sourceId : sources) {
            List<String> path = new ArrayList<>();
            Set<String> visitedOnPath = new HashSet<>();
            walkAndCheck(sourceId, successors, visitedOnPath, path, doc, builder);
        }
    }

    private void walkAndCheck(String cur,
                              Map<String, List<String>> successors,
                              Set<String> visitedOnPath,
                              List<String> path,
                              DagDocument doc,
                              DagValidationResult.Builder builder) {
        if (visitedOnPath.contains(cur)) {
            return; // 防环(理论上 AcyclicRule 已保证,但递归时仍防御)
        }
        visitedOnPath.add(cur);
        path.add(cur);
        List<String> nextList = successors.getOrDefault(cur, List.of());
        if (nextList.isEmpty()) {
            // sink;检查路径
            checkPathRemoveBgFirst(path, doc, builder);
        } else {
            for (String next : nextList) {
                walkAndCheck(next, successors, visitedOnPath, path, doc, builder);
            }
        }
        path.remove(path.size() - 1);
        visitedOnPath.remove(cur);
    }

    private void checkPathRemoveBgFirst(List<String> path,
                                        DagDocument doc,
                                        DagValidationResult.Builder builder) {
        // 找第一个 remove_bg 节点
        int firstRemoveBgIdx = -1;
        for (int i = 0; i < path.size(); i++) {
            String nodeId = path.get(i);
            DagNode node = doc.nodes().stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst().orElse(null);
            if (node != null && node.tool() == PixelTool.REMOVE_BG) {
                firstRemoveBgIdx = i;
                break;
            }
        }
        if (firstRemoveBgIdx < 0) {
            return; // 路径不含 remove_bg
        }
        // remove_bg 必须是路径上"source 之后的第一个节点":
        //   path[0] 是 source(无前驱);
        //   若 remove_bg 就在 path[0](source 节点本身就是 remove_bg)→ 合法;
        //   否则 path[1] 必须 == remove_bg(firstRemoveBgIdx == 1);
        //   当 path[1] 是其他节点(firstRemoveBgIdx > 1)算违规;
        //   path 长度仅 1 且 path[0] 不是 remove_bg → 不应发生(source 节点本身需是工具)
        if (path.size() > 1 && firstRemoveBgIdx > 1) {
            String sourceId = path.get(0);
            String firstOp = path.get(1);
            String removeBgId = path.get(firstRemoveBgIdx);
            builder.add("DAG_INVALID_OP_ORDER",
                "remove_bg 必须是逐图节点序列首位:source=" + sourceId
                    + ", 第一个操作=" + firstOp + ", remove_bg=" + removeBgId,
                Map.of("source", sourceId,
                    "firstOp", firstOp,
                    "removeBg", removeBgId));
        }
    }
}