package com.etherealstar.pixflow.module.dag.parser;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.schema.ToolType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对解析所得 DAG 做确定性规范化排序（需求 6.4）。
 *
 * <p>需求 6.4 要求当 {@code convert_format} 与 {@code compress} 同时出现时，相同指令必须始终产出
 * 一致的先后顺序。该约束在两处落实：
 * <ol>
 *   <li>{@link DagPromptManager} 在提示词中显式规定二者共现时的固定顺序；</li>
 *   <li>本规范化器作为服务端的二次保障——对 DAG 做确定性拓扑排序，使节点声明顺序与边顺序对于
 *       同一图结构唯一确定，从而消除 LLM 输出中节点书写次序带来的非确定性。</li>
 * </ol>
 *
 * <p>规范化采用 Kahn 拓扑选择：每一步在所有「入度为 0 且未放置」的就绪节点中按确定性比较器挑选一个。
 * 比较器对 {@code convert_format} 与 {@code compress} 这一对赋予固定优先级
 * （{@code convert_format} 先于 {@code compress}，即「先转格式再压缩至目标体积」），
 * 其余节点按其在原始声明中的位置保持稳定顺序。该比较器仅在二者「同时就绪」（彼此间无依赖边强制顺序）
 * 时影响其相对次序，恰好对应需求 6.4 所述的共现确定性。</p>
 *
 * <p>若 DAG 含环（无法完成拓扑排序），本规范化器不抛错，而是原样返回节点顺序——环的检测与拒绝由
 * DAG_Validator 在确认执行前负责（需求 7.3），解析阶段不在此处提前失败。</p>
 */
@Component
public class DagNormalizer {

    private static final String CONVERT_FORMAT = ToolType.CONVERT_FORMAT.getToolName();
    private static final String COMPRESS = ToolType.COMPRESS.getToolName();

    /**
     * 对 DAG 进行确定性规范化：节点按确定性拓扑序重排，边去重并按 (from, to) 字典序排序。
     *
     * @param dag 待规范化的 DAG，可为 {@code null}
     * @return 规范化后的新 DAG；入参为 {@code null} 时返回 {@code null}
     */
    public Dag normalize(Dag dag) {
        if (dag == null) {
            return null;
        }
        List<DagNode> orderedNodes = canonicalNodeOrder(dag);
        List<DagEdge> orderedEdges = canonicalEdges(dag.getEdges());
        return new Dag(orderedNodes, orderedEdges);
    }

    private List<DagNode> canonicalNodeOrder(Dag dag) {
        List<DagNode> nodes = dag.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return new ArrayList<>();
        }

        // 原始声明位置索引（用于在非 convert_format/compress 对之间保持稳定顺序）
        Map<String, Integer> originalIndex = new HashMap<>();
        Map<String, DagNode> nodeById = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            DagNode node = nodes.get(i);
            if (node != null && node.getId() != null) {
                originalIndex.putIfAbsent(node.getId(), i);
                nodeById.putIfAbsent(node.getId(), node);
            }
        }

        // 邻接表与入度表（仅统计两端均存在的边，去重避免重复计数）
        Map<String, Set<String>> successors = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeById.keySet()) {
            successors.put(id, new LinkedHashSet<>());
            inDegree.put(id, 0);
        }
        if (dag.getEdges() != null) {
            Set<DagEdge> seen = new HashSet<>();
            for (DagEdge edge : dag.getEdges()) {
                if (edge == null || edge.getFrom() == null || edge.getTo() == null) {
                    continue;
                }
                if (!nodeById.containsKey(edge.getFrom()) || !nodeById.containsKey(edge.getTo())) {
                    continue;
                }
                if (!seen.add(edge)) {
                    continue;
                }
                if (successors.get(edge.getFrom()).add(edge.getTo())) {
                    inDegree.merge(edge.getTo(), 1, Integer::sum);
                }
            }
        }

        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        // 尚未放置的 convert_format 节点计数：只要还有 convert_format 待放置，便延后所有
        // compress 节点的放置（除非无其他就绪节点而被迫选择），以确定性地保证「先 convert_format
        // 后 compress」的共现顺序（需求 6.4）。该选择规则为一致的全序选择，避免比较器的不可传递问题。
        int pendingConvertFormat = 0;
        for (DagNode node : nodeById.values()) {
            if (CONVERT_FORMAT.equals(toolOf(node))) {
                pendingConvertFormat++;
            }
        }

        List<DagNode> ordered = new ArrayList<>(nodeById.size());
        while (!ready.isEmpty()) {
            String picked = selectNext(ready, nodeById, originalIndex, pendingConvertFormat > 0);
            ready.remove(picked);
            DagNode pickedNode = nodeById.get(picked);
            ordered.add(pickedNode);
            if (CONVERT_FORMAT.equals(toolOf(pickedNode))) {
                pendingConvertFormat--;
            }
            for (String succ : successors.get(picked)) {
                if (inDegree.merge(succ, -1, Integer::sum) == 0) {
                    ready.add(succ);
                }
            }
        }

        // 存在环：无法排入全部节点，规范化交由校验阶段处理，此处保持原始顺序
        if (ordered.size() < nodeById.size()) {
            return new ArrayList<>(nodes);
        }
        return ordered;
    }

    /**
     * 从就绪节点集合中确定性地选择下一个放置的节点。
     *
     * <p>选择规则（一致全序，避免比较器不可传递问题）：当仍有 {@code convert_format} 节点待放置时，
     * 优先在「非 compress」的就绪节点中按原始声明位置选择最小者；仅当就绪集合中只剩 {@code compress}
     * 节点（无其他可选）时才被迫选择 {@code compress}。否则（无待放置 convert_format）直接按原始
     * 声明位置选择最小者。该规则保证二者共现且无强制先后边时 {@code convert_format} 始终先于
     * {@code compress}（需求 6.4），同时对其余节点保持稳定的声明顺序。</p>
     */
    private String selectNext(List<String> ready,
                              Map<String, DagNode> nodeById,
                              Map<String, Integer> originalIndex,
                              boolean convertFormatPending) {
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        if (convertFormatPending) {
            for (String id : ready) {
                if (COMPRESS.equals(toolOf(nodeById.get(id)))) {
                    continue;
                }
                int idx = originalIndex.getOrDefault(id, Integer.MAX_VALUE);
                if (idx < bestIndex) {
                    bestIndex = idx;
                    best = id;
                }
            }
            if (best != null) {
                return best;
            }
        }
        for (String id : ready) {
            int idx = originalIndex.getOrDefault(id, Integer.MAX_VALUE);
            if (idx < bestIndex) {
                bestIndex = idx;
                best = id;
            }
        }
        return best;
    }

    private String toolOf(DagNode node) {
        return node == null || node.getTool() == null ? "" : node.getTool();
    }

    private List<DagEdge> canonicalEdges(List<DagEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return new ArrayList<>();
        }
        List<DagEdge> result = new ArrayList<>();
        Set<DagEdge> seen = new HashSet<>();
        for (DagEdge edge : edges) {
            if (edge == null || edge.getFrom() == null || edge.getTo() == null) {
                continue;
            }
            if (seen.add(edge)) {
                result.add(new DagEdge(edge.getFrom(), edge.getTo()));
            }
        }
        result.sort(Comparator.comparing(DagEdge::getFrom).thenComparing(DagEdge::getTo));
        return result;
    }
}
