package com.etherealstar.pixflow.module.task.dto;

import java.util.List;

/**
 * 确认执行响应（需求 8.5、8.6、8.7、11.3）。
 *
 * <p>同步执行完成后返回任务终态与计数，并附带成功结果图的预览 URL（按 {@code asset_image.id} 升序
 * 取前 {@code min(3, n)} 张，需求 8.6、8.7）。</p>
 *
 * @param taskId            任务 id
 * @param status            任务终态：2 完成 / 3 失败
 * @param totalCount        处理的图片总数
 * @param doneCount         已完成处理的图片数
 * @param resultPreviewUrls 成功结果图预览 URL（前 min(3, n) 张）
 */
public record ConfirmResponse(
        Long taskId,
        int status,
        int totalCount,
        int doneCount,
        List<String> resultPreviewUrls) {
}
