package com.etherealstar.pixflow.module.dag.parser;

/**
 * 解析所得 DAG 中某个工具节点缺失的一项必填参数（需求 6.2）。
 *
 * <p>对应 {@code POST /send} 响应中 {@code missingParams} 数组的单个元素：
 * <pre>{ "nodeId": "n5", "param": "text" }</pre>
 *
 * <p>对于「二选一」（one-of）约束（如 {@code watermark} 的 {@code text} 或 {@code image} 须至少其一），
 * 当二者均未提供时，{@code param} 以 {@code "text|image"} 形式表示该组任一成员均可满足。
 *
 * @param nodeId 缺失参数所属的节点标识（DAG JSON 中节点 {@code id}）
 * @param param  缺失的参数名（或以 {@code |} 连接的二选一组成员）
 */
public record MissingParam(String nodeId, String param) {

    public MissingParam {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        if (param == null || param.isBlank()) {
            throw new IllegalArgumentException("param 不能为空");
        }
    }
}
