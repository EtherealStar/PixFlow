package com.pixflow.module.dag.expand;

import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.ir.ValidatedDag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * GroupPreflight:确认边界 HITL 张数预检(纯函数,见 dag.md §6.3)。
 *
 * <p>输入:ValidatedDag + 各组实际成员计数(由 conversation/task 提供);<br>
 * 输出:每个含 expected_count 的 compose_group 的"期望 vs 实际"差异列表。
 *
 * <p>dag 不发起 HITL、不碰令牌;只算差异;拦截/二次确认由 conversation + permission 决策。
 */
@Component
public class GroupPreflight {

    public List<PreflightDifference> preflight(ValidatedDag dag,
                                               Map<String, Integer> actualGroupCounts) {
        List<PreflightDifference> diffs = new ArrayList<>();
        for (DagNode node : dag.nodes()) {
            if (node.tool() != PixelTool.COMPOSE_GROUP) {
                continue;
            }
            Object expectedObj = node.params().get("expected_count");
            if (!(expectedObj instanceof Number n)) {
                continue; // 无 expected_count 不产生差异
            }
            int expected = n.intValue();
            // 组支路以 groupKey 标识;但 compose_group 节点的 id 不一定是 groupKey。
            // 约定:preflight 入参 actualGroupCounts 以 compose_group 节点 id 为 key。
            int actual = actualGroupCounts == null ? 0 : actualGroupCounts.getOrDefault(node.id(), 0);
            if (expected != actual) {
                diffs.add(new PreflightDifference(
                    node.id(),
                    expected,
                    actual,
                    "expected_count=" + expected + " 与实际成员数 " + actual + " 不一致"));
            }
        }
        return diffs;
    }

    /** 单条差异记录。 */
    public record PreflightDifference(
        String composeNodeId,
        int expectedCount,
        int actualCount,
        String message
    ) {}
}