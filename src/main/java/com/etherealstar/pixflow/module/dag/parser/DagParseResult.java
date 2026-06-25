package com.etherealstar.pixflow.module.dag.parser;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import java.util.List;

/**
 * DAG_Parser 的解析结果（需求 6.2、6.3、6.5）。
 *
 * <p>承载 {@code POST /send} 响应中与解析相关的字段，由上层 Conversation_Module 在补充
 * {@code messageId} 后返回给前端。两种终态：
 * <ul>
 *   <li><strong>缺参追问</strong>（{@link #missing}）：存在工具节点缺少必填参数。逐项列出全部缺失项
 *       （{@link #missingParams}），{@code needConfirm=true}，不返回 {@code dagPreview}，
 *       且不生成可执行任务（{@code taskId=null}）。系统不以任何默认值自动填充缺失的必填参数（需求 6.5）。</li>
 *   <li><strong>预览待确认</strong>（{@link #preview}）：全部必填参数齐备。返回包含 nodes 与 edges 的
 *       {@code dagPreview}，{@code needConfirm=true}，确认前不生成任务（{@code taskId=null}）。</li>
 * </ul>
 *
 * <p>注意：本结果对象中的 {@code taskId} 恒为 {@code null}——任务仅在用户 {@code /confirm} 后由
 * Task_Manager 创建（需求 6.3）。
 *
 * @param needConfirm   是否需要用户确认（两种终态均为 true）
 * @param missingParams 缺失的必填参数清单（预览态为空列表）
 * @param dagPreview    解析所得 DAG 预览（缺参态为 {@code null}）
 * @param reply         面向用户的自然语言回复（追问内容或确认提示）
 * @param taskId        关联任务标识，确认前恒为 {@code null}
 */
public record DagParseResult(
        boolean needConfirm,
        List<MissingParam> missingParams,
        Dag dagPreview,
        String reply,
        Long taskId) {

    public DagParseResult {
        missingParams = missingParams == null ? List.of() : List.copyOf(missingParams);
    }

    /** 是否存在缺失的必填参数（缺参追问态）。 */
    public boolean hasMissingParams() {
        return !missingParams.isEmpty();
    }

    /**
     * 构造「缺参追问」结果：{@code needConfirm=true}、无预览、无任务。
     *
     * @param missingParams 全部缺失项（非空）
     * @param reply         追问回复文本
     */
    public static DagParseResult missing(List<MissingParam> missingParams, String reply) {
        return new DagParseResult(true, missingParams, null, reply, null);
    }

    /**
     * 构造「预览待确认」结果：{@code needConfirm=true}、带预览、无任务。
     *
     * @param dagPreview 解析所得 DAG（非空）
     * @param reply      确认提示文本
     */
    public static DagParseResult preview(Dag dagPreview, String reply) {
        return new DagParseResult(true, List.of(), dagPreview, reply, null);
    }
}
