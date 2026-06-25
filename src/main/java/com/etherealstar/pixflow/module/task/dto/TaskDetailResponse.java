package com.etherealstar.pixflow.module.task.dto;

import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务详情响应（需求 12.4、12.5）。
 *
 * <p>返回 {@code dagJson}、状态、计数与结果列表；失败结果项内含 {@code errorMsg}（需求 11.3）。</p>
 *
 * @param id           任务 id
 * @param conversationId 来源对话 id
 * @param packageId    处理的素材包 id
 * @param dagJson      任务对应的 DAG JSON
 * @param status       任务状态：0 待执行 / 1 执行中 / 2 完成 / 3 失败
 * @param totalCount   总图片数
 * @param doneCount    已完成数
 * @param createdAt    创建时间
 * @param finishedAt   执行结束时刻
 * @param results      结果列表
 */
public record TaskDetailResponse(
        Long id,
        Long conversationId,
        Long packageId,
        String dagJson,
        Integer status,
        Integer totalCount,
        Integer doneCount,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        List<TaskResultItem> results) {

    public static TaskDetailResponse from(ProcessTask task, List<TaskResultItem> results) {
        return new TaskDetailResponse(
                task.getId(),
                task.getConversationId(),
                task.getPackageId(),
                task.getDagJson(),
                task.getStatus(),
                task.getTotalCount(),
                task.getDoneCount(),
                task.getCreatedAt(),
                task.getFinishedAt(),
                results);
    }
}
