package com.pixflow.module.dag.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * dag 模块错误码(对齐 dag.md §10 错误归一化清单)。
 *
 * <p>校验类错误(VALIDATION/TERMINATE)发生在提案/确认边界,不创建任务;执行类(SKIP)发生在
 * task worker 调用执行器时,隔离支路。两类不混淆。
 */
public enum DagErrorCode implements ErrorCode {
    // 校验类(提案/确认边界,TERMINATE)
    DAG_INVALID_STRUCTURE(ErrorCategory.VALIDATION),
    DAG_NODE_LIMIT_EXCEEDED(ErrorCategory.VALIDATION),
    DAG_UNKNOWN_TOOL(ErrorCategory.VALIDATION),
    DAG_INVALID_PARAMS(ErrorCategory.VALIDATION),
    DAG_HAS_CYCLE(ErrorCategory.VALIDATION),
    DAG_INVALID_GROUP_BRANCH(ErrorCategory.VALIDATION),
    DAG_INVALID_OP_ORDER(ErrorCategory.VALIDATION),
    DAG_PAYLOAD_HASH_MISMATCH(ErrorCategory.VALIDATION),
    DAG_SCHEMA_INCOMPATIBLE(ErrorCategory.VALIDATION),

    // 提案状态类
    DAG_PLAN_NOT_FOUND(ErrorCategory.NOT_FOUND),
    DAG_PLAN_EXPIRED(ErrorCategory.NOT_FOUND),
    DAG_PLAN_ALREADY_CONFIRMED(ErrorCategory.BUSINESS_RULE),

    // 执行类(隔离支路)
    /** 单元执行失败的归一化外壳,category 由 ErrorNormalizer 据原始异常决定(多为 SKIP) */
    DAG_UNIT_EXECUTION_FAILED(ErrorCategory.IMAGE_PROCESSING),
    DAG_UNIT_TIMEOUT(ErrorCategory.DEPENDENCY),
    DAG_SOURCE_BYTES_TOO_LARGE(ErrorCategory.VALIDATION),
    DAG_GROUP_MEMBER_MISSING(ErrorCategory.NOT_FOUND);

    private final ErrorCategory category;

    DagErrorCode(ErrorCategory category) {
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}