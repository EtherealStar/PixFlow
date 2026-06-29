package com.pixflow.module.imagegen.exec;

/**
 * 无状态单图重绘执行器 SPI(对齐 imagegen.md §8.2)。
 *
 * <p>由 {@code module/task} 在生成式 process_task worker 内消费;
 * 实现方为 {@link DefaultImageGenExecutor}。
 *
 * <p>约束:
 * <ul>
 *   <li>无状态:不持久化任何东西、不发进度、不写 process_result、不感知取消</li>
 *   <li>幂等:重跑覆盖同一 GENERATED key,无副作用累积</li>
 *   <li>失败语义:抛 {@link com.pixflow.common.error.PixFlowException}({@code ImagegenErrorCode}),
 *       由 task 决定失败隔离与重试策略</li>
 * </ul>
 */
public interface ImageGenExecutor {

    /**
     * 把单张源图重绘为一张新图并落 {@code GENERATED} 桶。
     *
     * @param spec 执行单元载荷(1 源图 → 1 重绘)
     * @return 生成产物(包含 ObjectRef、contentType、TokenUsage)
     * @throws com.pixflow.common.error.PixFlowException 执行期任意失败:
     *         {@code IMAGEGEN_OUTPUT_BYTES_TOO_LARGE} / {@code IMAGEGEN_STORAGE_WRITE_FAILED}
     *         / {@code IMAGEGEN_CONTENT_POLICY_VIOLATION} 或 ai 抛出的原样异常
     */
    GeneratedArtifact redraw(GenerativeUnitSpec spec);
}