package com.etherealstar.pixflow.module.task.dto;

import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import java.time.LocalDateTime;

/**
 * 任务列表项（需求 12.1）。
 *
 * @param id           任务 id
 * @param conversationId 来源对话 id
 * @param packageId    处理的素材包 id
 * @param status       任务状态：0 待执行 / 1 执行中 / 2 完成 / 3 失败
 * @param totalCount   总图片数
 * @param doneCount    已完成数
 * @param createdAt    创建时间
 * @param finishedAt   执行结束时刻（未结束为 null）
 */
public record TaskListItem(
        Long id,
        Long conversationId,
        Long packageId,
        Integer status,
        Integer totalCount,
        Integer doneCount,
        LocalDateTime createdAt,
        LocalDateTime finishedAt) {

    public static TaskListItem from(ProcessTask task) {
        return new TaskListItem(
                task.getId(),
                task.getConversationId(),
                task.getPackageId(),
                task.getStatus(),
                task.getTotalCount(),
                task.getDoneCount(),
                task.getCreatedAt(),
                task.getFinishedAt());
    }
}
