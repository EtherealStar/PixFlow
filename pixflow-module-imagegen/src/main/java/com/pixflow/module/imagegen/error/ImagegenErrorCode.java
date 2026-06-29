package com.pixflow.module.imagegen.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * imagegen 模块错误码(对齐 imagegen.md §十二 / §十六.8)。
 *
 * <p>10 条全部并入 {@code common} 启动期聚合测试(通过各模块独立 enum 实现统一契约)。
 * 提案侧 / 执行侧 / 确认侧三类错误按 category 区分,recovery 走 {@link com.pixflow.common.error.RecoveryHint}。
 *
 * <p>命名规范:{@code IMAGEGEN_*};见 module/imagegen.md §十二的 10 条清单。
 */
public enum ImagegenErrorCode implements ErrorCode {
    // —— 提案侧校验(proposal)——
    /** source_image_ids 含不存在的 imageId */
    IMAGEGEN_SOURCE_IMAGE_NOT_FOUND(ErrorCategory.NOT_FOUND),
    /** 源图不归属当前会话绑定素材包 */
    IMAGEGEN_SOURCE_NOT_IN_PACKAGE(ErrorCategory.BUSINESS_RULE),
    /** prompt 为空 / 长度越界 / params 白名单外键 */
    IMAGEGEN_PROMPT_INVALID(ErrorCategory.VALIDATION),
    /** 源图张数超过 max-source-images(提示 agent 拆分提案) */
    IMAGEGEN_TOO_MANY_SOURCES(ErrorCategory.BUSINESS_RULE),
    /** 源图内容类型不在 supported-types 白名单 */
    IMAGEGEN_UNSUPPORTED_SOURCE_TYPE(ErrorCategory.VALIDATION),

    // —— 执行侧(exec / 隔离单图)——
    /** 生成图字节超过 max-output-bytes(单位隔离) */
    IMAGEGEN_OUTPUT_BYTES_TOO_LARGE(ErrorCategory.VALIDATION),
    /** 写 GENERATED 桶失败(细化 storage 通用错,便于 UI 提示"生图结果未保存") */
    IMAGEGEN_STORAGE_WRITE_FAILED(ErrorCategory.STORAGE),
    /** 供应商内容审查拒生图(细化 ai 的 MODEL_PROVIDER_ERROR,便于 UI 提示"生图被供应商拒绝") */
    IMAGEGEN_CONTENT_POLICY_VIOLATION(ErrorCategory.PROVIDER),

    // —— 确认侧(confirm / TERMINATE)——
    /** confirm 端点重算 payloadHash 与 token claims 不一致(对称 dag 的 DAG_PAYLOAD_HASH_MISMATCH) */
    IMAGEGEN_PAYLOAD_HASH_MISMATCH(ErrorCategory.VALIDATION),
    /** confirm 边界按 planId 取回提案落空(过期 / 已消费) */
    IMAGEGEN_PLAN_NOT_FOUND(ErrorCategory.NOT_FOUND);

    private final ErrorCategory category;

    ImagegenErrorCode(ErrorCategory category) {
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