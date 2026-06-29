package com.pixflow.module.dag.ir;

import java.util.Map;
import java.util.Objects;

/**
 * DAG 节点:不可变 record。
 *
 * <p>参数 params 是 DAG 校验前浅解析得到的 Map;校验通过后该 map 已被 schema 验证。
 * 防御性拷贝避免下游持有者后续修改污染源数据。
 */
public record DagNode(String id, PixelTool tool, Map<String, Object> params) {
    public DagNode {
        Objects.requireNonNull(id, "node.id");
        Objects.requireNonNull(tool, "node.tool");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
