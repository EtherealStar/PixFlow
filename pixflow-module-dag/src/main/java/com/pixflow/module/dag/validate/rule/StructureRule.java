package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.validate.DagValidationResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * S1 结构规则:节点 id 非空且唯一;nodes/edges 数组非空。
 *
 * <p>这是所有后续规则的前置:无唯一 id,边引用完整性/无环判定无从说起。
 */
public class StructureRule {

    public String name() {
        return "STRUCTURE";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        if (doc.nodes().isEmpty()) {
            builder.add("DAG_INVALID_STRUCTURE", "DAG 至少包含 1 个节点");
            return;
        }
        Map<String, Integer> idOccurrences = new HashMap<>();
        for (int i = 0; i < doc.nodes().size(); i++) {
            DagNode node = doc.nodes().get(i);
            String id = node.id();
            if (id == null || id.isBlank()) {
                builder.add("DAG_INVALID_STRUCTURE", "nodes[" + i + "].id 不能为空");
                continue;
            }
            idOccurrences.merge(id, 1, Integer::sum);
        }
        // 重复 id
        Set<String> duplicates = new HashSet<>();
        for (Map.Entry<String, Integer> e : idOccurrences.entrySet()) {
            if (e.getValue() > 1) {
                duplicates.add(e.getKey());
            }
        }
        if (!duplicates.isEmpty()) {
            builder.add("DAG_INVALID_STRUCTURE",
                "节点 id 重复: " + String.join(", ", duplicates),
                Map.of("duplicates", duplicates));
        }
    }
}