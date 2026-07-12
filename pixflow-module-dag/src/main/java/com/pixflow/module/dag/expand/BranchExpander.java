package com.pixflow.module.dag.expand;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.dag.exec.ExecutionEdge;
import com.pixflow.module.dag.exec.ExecutionStep;
import com.pixflow.module.dag.exec.GroupStep;
import com.pixflow.module.dag.exec.LocalImageStep;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.exec.LocalImageBindingSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * BranchExpander:把 TypedExecutionPlan + 图片成员展开为 List&lt;ExecutableBranch&gt;。
 *
 * <p>纯函数、无状态:同输入 → 同输出(含 branchId);被 task 调用于 fan-out 单元计划。
 *
 * <p>展开规则:
 * <ul>
 *   <li>对每个 source 节点做拓扑 DFS,枚举到 sink 的每条路径</li>
 *   <li>遇到 compose_group → 拆为 perMemberOps/compose/postOps 三段</li>
 *   <li>每条 source→sink 路径 = 一条 ExecutableBranch(普通支路)</li>
 *   <li>含 compose_group 的路径 = 一条组支路(per 组 groupKey)</li>
 * </ul>
 *
 * <p>branchId 派生:用路径节点 id 序列 + 每节点 canonical params 经 sha256(见 BranchId)。
 */
@Component
public class BranchExpander {

    /**
     * @param dag 已校验 DAG
     * @param images 由调用方(task)喂入的中立图片成员列表;普通支路按每张图展开,
     *               组支路按 (groupKey) 分组,组内按 viewId 排序
     * @return 可执行支路列表(每张图或每组至少一条)
     */
    public List<ExecutableBranch> expand(TypedExecutionPlan dag, List<ImageDescriptor> images) {
        if (images == null) {
            images = List.of();
        }
        // 构建邻接表
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, List<String>> predecessors = new HashMap<>();
        for (ExecutionStep node : dag.steps()) {
            successors.computeIfAbsent(node.nodeId(), k -> new ArrayList<>());
            predecessors.computeIfAbsent(node.nodeId(), k -> new ArrayList<>());
        }
        for (ExecutionEdge edge : dag.edges()) {
            successors.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            predecessors.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
        // 找 source 节点(无前驱)
        List<String> sources = new ArrayList<>();
        for (ExecutionStep node : dag.steps()) {
            if (predecessors.get(node.nodeId()).isEmpty()) {
                sources.add(node.nodeId());
            }
        }
        if (sources.isEmpty()) {
            return List.of();
        }
        // 找 compose_group 节点(用于拆 perMember/post)
        GroupStep composeNode = dag.steps().stream()
            .filter(GroupStep.class::isInstance).map(GroupStep.class::cast)
            .findFirst()
            .orElse(null);

        // 用 BFS/DFS 枚举每条 source→sink 路径
        List<List<String>> allPaths = new ArrayList<>();
        for (String src : sources) {
            walk(src, successors, new ArrayList<>(), new HashSet<>(), allPaths);
        }

        // 按路径生成 ExecutableBranch
        List<ExecutableBranch> branches = new ArrayList<>();
        if (composeNode == null) {
            // 普通支路:每条路径 × 每张图
            for (List<String> path : allPaths) {
                String branchId = pathBranchId(dag, path);
                ExecutableBranch.EncodeTarget encode = inferEncodeTarget(dag, path);
                List<ExecutionStep> ops = resolveNodes(dag, path);
                for (ImageDescriptor img : images) {
                    branches.add(new ExecutableBranch(
                        UnitKind.BRANCH,
                        branchId,
                        img.imageId(),
                        ops,
                        null,
                        List.of(),
                        encode
                    ));
                }
            }
        } else {
            // 组支路:取 compose 的所有前驱节点(去重,拓扑序)作为 perMemberOps;
            // 后续路径(从 compose 出去)为 postOps。
            // 普通成员(非分组的图)按不含 compose 的兄弟路径生成 BRANCH。
            Map<String, List<ImageDescriptor>> byGroup = groupImages(images);
            List<String> composePredecessors = predecessors.getOrDefault(composeNode.nodeId(), List.of());
            List<ExecutionStep> perMemberOps = resolveNodes(dag, composePredecessors);

            // 找从 compose 出发的所有后继路径(共享 postOps)
            List<String> postPath = collectPostPath(composeNode.nodeId(), successors);
            List<ExecutionStep> postOps = resolveNodes(dag, postPath);

            // 组支路的 branchId 由 perMemberOps + composeNode + postOps 派生
            List<String> composeBranchPath = new ArrayList<>();
            composeBranchPath.addAll(composePredecessors);
            composeBranchPath.add(composeNode.nodeId());
            composeBranchPath.addAll(postPath);
            String composeBranchId = pathBranchId(dag, composeBranchPath);
            ExecutableBranch.EncodeTarget composeEncode = inferEncodeTarget(dag, composeBranchPath);

            for (Map.Entry<String, List<ImageDescriptor>> e : byGroup.entrySet()) {
                branches.add(new ExecutableBranch(
                    UnitKind.GROUP,
                    composeBranchId,
                    e.getKey(),
                    perMemberOps,
                    composeNode,
                    postOps,
                    composeEncode
                ));
            }
            // 普通成员:不分组(groupKey 空)的 images
            List<ImageDescriptor> singles = images.stream()
                .filter(img -> img.groupKey() == null || img.groupKey().isBlank())
                .toList();
            for (List<String> otherPath : allPaths) {
                if (otherPath.contains(composeNode.nodeId())) {
                    continue; // 只展开"不含 compose"的兄弟路径作为普通成员
                }
                String otherBranchId = pathBranchId(dag, otherPath);
                ExecutableBranch.EncodeTarget otherEncode = inferEncodeTarget(dag, otherPath);
                List<ExecutionStep> otherOps = resolveNodes(dag, otherPath);
                for (ImageDescriptor img : singles) {
                    branches.add(new ExecutableBranch(
                        UnitKind.BRANCH,
                        otherBranchId,
                        img.imageId(),
                        otherOps,
                        null,
                        List.of(),
                        otherEncode
                    ));
                }
            }
        }
        return branches;
    }

    /** 沿 successors 收集从 compose 出发到 sink 的唯一后继路径(无分支时是单链)。 */
    private List<String> collectPostPath(String composeId, Map<String, List<String>> successors) {
        List<String> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cur = composeId;
        while (cur != null && !visited.contains(cur)) {
            visited.add(cur);
            List<String> next = successors.getOrDefault(cur, List.of());
            if (next.isEmpty()) {
                break;
            }
            // 简单实现:每个节点取第一个后继(组支路 post 链通常线性)
            cur = next.get(0);
            if (!cur.equals(composeId)) {
                path.add(cur);
            }
        }
        return path;
    }

    private void walk(String cur,
                       Map<String, List<String>> successors,
                       List<String> path,
                       Set<String> visited,
                       List<List<String>> allPaths) {
        if (visited.contains(cur)) {
            return;
        }
        visited.add(cur);
        path.add(cur);
        List<String> nextList = successors.getOrDefault(cur, List.of());
        if (nextList.isEmpty()) {
            allPaths.add(new ArrayList<>(path));
        } else {
            for (String next : nextList) {
                walk(next, successors, path, visited, allPaths);
            }
        }
        path.remove(path.size() - 1);
        visited.remove(cur);
    }

    private List<ExecutionStep> resolveNodes(TypedExecutionPlan dag, List<String> pathIds) {
        Map<String, ExecutionStep> byId = new LinkedHashMap<>();
        for (ExecutionStep node : dag.steps()) {
            byId.put(node.nodeId(), node);
        }
        List<ExecutionStep> result = new ArrayList<>();
        for (String id : pathIds) {
            ExecutionStep node = byId.get(id);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    private String pathBranchId(TypedExecutionPlan dag, List<String> path) {
        List<ExecutionStep> nodes = resolveNodes(dag, path);
        List<Map<String, Object>> params = new ArrayList<>();
        for (ExecutionStep n : nodes) {
            params.add(Map.of("step", n.toString()));
        }
        return BranchId.derive(path, params);
    }

    private ExecutableBranch.EncodeTarget inferEncodeTarget(TypedExecutionPlan dag, List<String> path) {
        // 末端节点若是 convert_format,用其格式;否则默认 JPEG
        List<ExecutionStep> nodes = resolveNodes(dag, path);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ExecutionStep n = nodes.get(i);
            if (n instanceof LocalImageStep local
                    && local.typedSpec() instanceof LocalImageBindingSpec.ConvertFormat spec) {
                return new ExecutableBranch.EncodeTarget(
                        spec.value().targetFormat().name(), spec.value().quality());
            }
        }
        return ExecutableBranch.EncodeTarget.defaultJpeg();
    }

    private Map<String, List<ImageDescriptor>> groupImages(List<ImageDescriptor> images) {
        Map<String, List<ImageDescriptor>> byGroup = new LinkedHashMap<>();
        for (ImageDescriptor img : images) {
            String key = img.groupKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(img);
        }
        // 按 viewId 排序(viewId 可空时保持插入序)
        for (List<ImageDescriptor> list : byGroup.values()) {
            list.sort(Comparator.comparing(img -> img.viewId() == null ? "" : img.viewId()));
        }
        return byGroup;
    }
}
