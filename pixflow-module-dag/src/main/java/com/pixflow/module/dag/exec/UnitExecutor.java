package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.expand.ExecutableBranch;

/**
 * 单元执行器接口(对齐 dag.md §8.1)。
 *
 * <p>三种实现:
 * <ul>
 *   <li>{@link PipelineUnitExecutor} 逐图支路</li>
 *   <li>{@link GroupUnitExecutor} 组支路</li>
 *   <li>{@link CopyUnitExecutor} 文案支路</li>
 * </ul>
 *
 * <p>**从不抛业务异常打断批次**:任何异常经 {@link com.pixflow.module.dag.error.DagErrorCode}
 * 归一化后返回 UnitOutcome.FAILED。
 */
public interface UnitExecutor {

    UnitOutcome execute(ExecutableBranch branch, UnitInput input);
}