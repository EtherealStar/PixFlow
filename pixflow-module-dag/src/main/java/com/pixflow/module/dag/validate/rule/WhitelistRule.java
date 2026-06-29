package com.pixflow.module.dag.validate.rule;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.validate.DagValidationResult;

/**
 * S3 白名单规则:每个节点的 tool 必须在 {@link com.pixflow.module.dag.ir.PixelTool} 枚举内。
 *
 * <p>DagJsonReader 浅解析阶段已拒绝未知 tool 抛 IllegalArgumentException;本规则是防御性兜底,
 * 若 DagDocument 经其他路径(直接构造 / 反序列化)进来时仍能拦下。
 */
public class WhitelistRule {

    public String name() {
        return "WHITELIST";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        for (int i = 0; i < doc.nodes().size(); i++) {
            DagNode node = doc.nodes().get(i);
            if (node.tool() == null) {
                builder.add("DAG_UNKNOWN_TOOL",
                    "nodes[" + i + "](" + node.id() + ").tool 为空");
            }
        }
    }
}