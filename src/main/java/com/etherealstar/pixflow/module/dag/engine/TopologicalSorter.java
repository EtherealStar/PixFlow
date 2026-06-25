package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对 DAG 节点做分层拓扑排序（需求 8.1、8.3）。
 *
 * <p>采用 Kahn 算法的分层变体：每一层包含当前所有入度为 0 的节点，移除该层后
 * 再生成下一层。该分层保证：对每条边 {@code from → to}，{@code from} 所在层
 * 严格早于 {@code to} 所在层，因此任一节点只会在其全部前驱节点完成（位于更早的层）
 * 之后才被执行。同一层内的节点之间无依赖关系，可交由线程池并行执行（需求 8.3）。</p>
 *
 * <p>若 DAG 中存在环，无法将全部节点排入分层，将抛出 {@link CycleDetectedException}
 * （需求 7.3 在校验阶段据此判定环的存在）。</p>
 */
@Component
public class TopologicalSorter {

    /**
     * 对给定 DAG 进行分层拓扑排序。
     *
     * @param dag 待排序的 DAG
     * @return 分层结果，外层 List 表示执行层（按执行先后顺序），内层 List 表示该层
     *         可并行执行的节点（按节点 id 升序，保证确定性）
     * @throws CycleDetectedException 当 DAG 中存在环时
     */
    public List<List<DagNode>> sortIntoLayers(Dag dag) {
        if (dag == null || dag.getNodes() == null || dag.getNodes().isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 建立 id -> 节点 的索引（后出现的同 id 节点以先出现者为准，保持稳定）
        Map<String, DagNode> nodeById = new HashMap<>();
        for (DagNode node : dag.getNodes()) {
            if (node != null && node.getId() != null) {
                nodeById.putIfAbsent(node.getId(), node);
            }
        }

        // 2. 构建邻接表与入度表（对重复边去重，仅统计两端都存在的边）
        Map<String, Set<String>> successors = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeById.keySet()) {
            successors.put(id, new LinkedHashSet<>());
            inDegree.put(id, 0);
        }

        Set<DagEdge> seenEdges = new HashSet<>();
        if (dag.getEdges() != null) {
            for (DagEdge edge : dag.getEdges()) {
                if (edge == null) {
                    continue;
                }
                String from = edge.getFrom();
                String to = edge.getTo();
                // 仅处理两端均为已知节点、且非自环之外的有效边；去重避免重复计数
                if (from == null || to == null
                        || !nodeById.containsKey(from) || !nodeById.containsKey(to)) {
                    continue;
                }
                if (!seenEdges.add(edge)) {
                    continue;
                }
                // 同一 from/to 即使 DagEdge 实例不同也只计一次
                if (successors.get(from).add(to)) {
                    inDegree.merge(to, 1, Integer::sum);
                }
            }
        }

        // 3. 分层 Kahn 算法
        List<List<DagNode>> layers = new ArrayList<>();
        List<String> currentLayer = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                currentLayer.add(entry.getKey());
            }
        }
        currentLayer.sort(Comparator.naturalOrder());

        int processed = 0;
        while (!currentLayer.isEmpty()) {
            List<DagNode> layerNodes = new ArrayList<>(currentLayer.size());
            List<String> nextLayer = new ArrayList<>();
            for (String id : currentLayer) {
                layerNodes.add(nodeById.get(id));
                processed++;
                for (String succ : successors.get(id)) {
                    int remaining = inDegree.merge(succ, -1, Integer::sum);
                    if (remaining == 0) {
                        nextLayer.add(succ);
                    }
                }
            }
            layers.add(layerNodes);
            nextLayer.sort(Comparator.naturalOrder());
            currentLayer = nextLayer;
        }

        // 4. 若仍有未处理节点，说明存在环
        if (processed < nodeById.size()) {
            List<String> remaining = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() > 0) {
                    remaining.add(entry.getKey());
                }
            }
            remaining.sort(Comparator.naturalOrder());
            throw new CycleDetectedException(remaining);
        }

        return layers;
    }

    /**
     * 将分层结果展平为一维拓扑序（用于无需并行信息的顺序执行场景）。
     *
     * @param dag 待排序的 DAG
     * @return 满足依赖约束的节点拓扑序
     * @throws CycleDetectedException 当 DAG 中存在环时
     */
    public List<DagNode> sort(Dag dag) {
        List<DagNode> ordered = new ArrayList<>();
        for (List<DagNode> layer : sortIntoLayers(dag)) {
            ordered.addAll(layer);
        }
        return ordered;
    }

    /**
     * 判断 DAG 是否存在环（供校验阶段使用，需求 7.3）。
     *
     * @param dag 待检查的 DAG
     * @return 存在环返回 true，否则 false
     */
    public boolean hasCycle(Dag dag) {
        try {
            sortIntoLayers(dag);
            return false;
        } catch (CycleDetectedException e) {
            return true;
        }
    }
}
