package com.pixflow.module.dag.expand;

import com.pixflow.module.dag.exec.GroupStep;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * GroupPreflight:确认边界 HITL 张数预检(纯函数,见 dag.md §6.3)。
 *
 * <p>输入:TypedExecutionPlan + 各组实际成员计数(由 conversation/task 提供);<br>
 * 输出:每个含 expected_count 的 compose_group 的"期望 vs 实际"差异列表。
 *
 * <p>dag 不发起 HITL、不碰令牌;只算差异;拦截/二次确认由 conversation + permission 决策。
 */
@Component
public class GroupPreflight {

    public List<PreflightDifference> preflight(TypedExecutionPlan dag,
                                               Map<String, Integer> actualGroupCounts) {
        List<PreflightDifference> diffs = new ArrayList<>();
        for (GroupStep node : dag.steps().stream().filter(GroupStep.class::isInstance)
                .map(GroupStep.class::cast).toList()) {
            if (node.expectedCount() <= 0) {
                continue; // 无 expected_count 不产生差异
            }
            int expected = node.expectedCount();
            Map<String, Integer> counts = actualGroupCounts == null ? Map.of() : actualGroupCounts;
            if (counts.isEmpty()) {
                diffs.add(new PreflightDifference(node.nodeId(), null, expected, 0,
                        "expected_count=" + expected + "，但素材包没有可执行组"));
                continue;
            }
            // compose_group 会展开到每个 groupKey，因此 expected_count 必须逐组成立，不能压成代表值。
            for (Map.Entry<String, Integer> group : counts.entrySet()) {
                int actual = group.getValue();
                if (expected != actual) {
                    diffs.add(new PreflightDifference(node.nodeId(), group.getKey(), expected, actual,
                            "组 " + group.getKey() + " 的 expected_count=" + expected
                                    + " 与实际成员数 " + actual + " 不一致"));
                }
            }
        }
        return diffs;
    }

    /** 单条差异记录。 */
    public record PreflightDifference(
        String composeNodeId,
        String groupKey,
        int expectedCount,
        int actualCount,
        String message
    ) {
    }
}
