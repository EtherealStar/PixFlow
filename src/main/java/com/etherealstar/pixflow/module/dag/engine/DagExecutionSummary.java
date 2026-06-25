package com.etherealstar.pixflow.module.dag.engine;

/**
 * DAG 执行结果摘要（需求 8.5、11.3–11.5）。
 *
 * <p>由 {@link DagExecutionEngine#execute} 返回，承载任务执行完成后的终态与计数，供任务管理层
 * （Task_Manager）组装响应使用。</p>
 *
 * @param taskId       任务 id
 * @param status       任务终态：2 完成（至少一条结果成功）/ 3 失败（全部结果失败）
 * @param totalCount   处理的图片总数
 * @param doneCount    已完成处理的图片数
 * @param resultCount  产出的结果记录总数（图片数 × 支路数）
 * @param successCount 成功结果数
 * @param failureCount 失败结果数
 */
public record DagExecutionSummary(
        Long taskId,
        int status,
        int totalCount,
        int doneCount,
        int resultCount,
        int successCount,
        int failureCount) {
}
