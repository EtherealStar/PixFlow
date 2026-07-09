package com.pixflow.harness.loop.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * loop 模块自治错误码（极少 —— loop 大部分错误向上抛给 web 层归一化）。
 *
 * <p>仅在 loop 自身装配失败 / 状态被破坏等场景使用；正常路径上不可恢复错误
 * 仍走 {@code common} 的现有错误码（如 {@code MODEL_PROVIDER_ERROR}），
 * 经 {@code ErrorRecorder} 落盘后向上抛。
 */
public enum LoopErrorCode implements ErrorCode {
    /** RuntimeState 字段在运行中被破坏（如 turnNo 越界、usage 负值）。 */
    LOOP_RUNTIME_STATE_CORRUPTED("LOOP_RUNTIME_STATE_CORRUPTED", ErrorCategory.INTERNAL),
    /** LoopProperties 配置非法（如 escalatedMaxOutputTokens ≤ 0）。 */
    LOOP_CONFIGURATION_INVALID("LOOP_CONFIGURATION_INVALID", ErrorCategory.INTERNAL),
    /** 回合边界被错误打破（如 TurnStopped 未在自然结束路径派发）。 */
    LOOP_TURN_BOUNDARY_VIOLATION("LOOP_TURN_BOUNDARY_VIOLATION", ErrorCategory.INTERNAL);

    private final String code;
    private final ErrorCategory category;

    LoopErrorCode(String code, ErrorCategory category) {
        this.code = code;
        this.category = category;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
