package com.etherealstar.pixflow.module.dag.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.dag.domain.Branch;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 拓扑执行顺序不变量属性测试（任务 12.9）。
 *
 * <p>Feature: pixflow, Property 24: 拓扑执行顺序不变量——对任意（无环）DAG，执行顺序中每个
 * 节点都出现在其全部前驱节点之后；即对每条边 {@code from -> to}，{@code from} 在执行序中
 * 严格先于 {@code to}。引擎按 {@link TopologicalSorter} 的拓扑序与 {@link BranchExpander}
 * 的源到汇支路序应用节点，二者均须满足该不变量。
 * Validates: Requirements 8.1
 *
 * <p>本测试为纯逻辑测试，不涉及真实图片、文案生成或外部 API。随机 DAG 通过「仅允许低索引指向
 * 高索引」的方式构造，从而保证无环。
 */
class TopologicalExecutionOrderPropertyTest {

    private final TopologicalSorter sorter = new TopologicalSorter();
    private final BranchExpander expander = new BranchExpander();

    /** 生成无环 DAG：n 个节点 n0..n(n-1)，边仅从较小索引指向较大索引（保证无环）。 */
    @Provide
    Arbitrary<Dag> acyclicDags() {
        return Arbitraries.integers().between(1, 8).flatMap(n -> {
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    pairs.add(new int[] {i, j});
                }
            }
            if (pairs.isEmpty()) {
                return Arbitraries.just(buildDag(n, pairs, List.of()));
            }
            Arbitrary<List<Boolean>> flags =
                    Arbitraries.of(true, false).list().ofSize(pairs.size());
            return flags.map(f -> buildDag(n, pairs, f));
        });
    }

    private Dag buildDag(int n, List<int[]> pairs, List<Boolean> flags) {
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(new DagNode("n" + i, "resize", new HashMap<>()));
        }
        List<DagEdge> edges = new ArrayList<>();
        for (int k = 0; k < pairs.size(); k++) {
            if (flags.get(k)) {
                edges.add(new DagEdge("n" + pairs.get(k)[0], "n" + pairs.get(k)[1]));
            }
        }
        return new Dag(nodes, edges);
    }

    @Property(tries = 200)
    void flattenedTopologicalOrderRespectsEveryEdge(@ForAll("acyclicDags") Dag dag) {
        List<DagNode> order = sorter.sort(dag);

        // 所有节点都被排入执行序
        assertThat(order).hasSameSizeAs(dag.getNodes());

        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i).getId(), i);
        }
        // 不变量：每条边的起点严格先于终点
        for (DagEdge edge : dag.getEdges()) {
            assertThat(position.get(edge.getFrom()))
                    .as("前驱 %s 必须先于 %s 执行", edge.getFrom(), edge.getTo())
                    .isLessThan(position.get(edge.getTo()));
        }
    }

    @Property(tries = 200)
    void everyBranchSequenceRespectsEdgeDirection(@ForAll("acyclicDags") Dag dag) {
        List<Branch> branches = expander.expand(dag);

        for (Branch branch : branches) {
            List<String> sequence = branch.getNodeSequence();
            assertThat(sequence).isNotEmpty();

            Map<String, Integer> pos = new HashMap<>();
            for (int i = 0; i < sequence.size(); i++) {
                pos.put(sequence.get(i), i);
            }
            // 支路序内若两端点都出现，则起点必须先于终点（每节点在前驱之后执行）
            for (DagEdge edge : dag.getEdges()) {
                Integer from = pos.get(edge.getFrom());
                Integer to = pos.get(edge.getTo());
                if (from != null && to != null) {
                    assertThat(from)
                            .as("支路 %s 内 %s 必须先于 %s",
                                    branch.getBranchId(), edge.getFrom(), edge.getTo())
                            .isLessThan(to);
                }
            }
        }
    }

    @Property(tries = 200)
    void acyclicDagIsNeverReportedAsCyclic(@ForAll("acyclicDags") Dag dag) {
        // 构造保证无环，排序器不应判定为有环
        assertThat(sorter.hasCycle(dag)).isFalse();
    }
}
