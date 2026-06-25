package com.etherealstar.pixflow.module.conversation.dto;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.parser.DagParseResult;
import com.etherealstar.pixflow.module.dag.parser.MissingParam;

import java.util.List;

/**
 * 发送消息响应（需求 5.3、6.2、6.3、6.5）。
 *
 * <p>用户消息持久化后，将其内容交由 DAG_Parser 解析，响应同时承载持久化消息标识与解析终态：
 * <ul>
 *   <li>缺参追问：{@code needConfirm=true}、{@code missingParams} 非空、{@code dagPreview=null}、{@code taskId=null}（需求 6.2、6.5）；</li>
 *   <li>预览待确认：{@code needConfirm=true}、{@code dagPreview} 非空、{@code taskId=null}（需求 6.3）。</li>
 * </ul>
 * 任务仅在 {@code /confirm} 后创建，故此处 {@code taskId} 恒为 {@code null}。</p>
 *
 * @param messageId     已持久化的用户消息 id
 * @param needConfirm   是否需要用户确认
 * @param missingParams 缺失的必填参数清单（预览态为空列表）
 * @param dagPreview    解析所得 DAG 预览（缺参态为 {@code null}）
 * @param reply         面向用户的自然语言回复
 * @param taskId        关联任务标识，确认前恒为 {@code null}
 */
public record SendMessageResponse(
        Long messageId,
        boolean needConfirm,
        List<MissingParam> missingParams,
        Dag dagPreview,
        String reply,
        Long taskId) {

    /**
     * 由持久化消息与解析结果组装响应。
     *
     * @param messageId   已持久化用户消息 id
     * @param parseResult DAG_Parser 解析结果
     */
    public static SendMessageResponse of(Long messageId, DagParseResult parseResult) {
        return new SendMessageResponse(
                messageId,
                parseResult.needConfirm(),
                parseResult.missingParams(),
                parseResult.dagPreview(),
                parseResult.reply(),
                parseResult.taskId());
    }
}
