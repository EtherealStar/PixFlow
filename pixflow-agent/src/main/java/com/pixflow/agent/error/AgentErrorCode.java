package com.pixflow.agent.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Agent 模块自治错误码。
 *
 * <p>对应 {@code agent.md §12.6} 错误归一化段：6 个自治码。
 *
 * <p>设计要点：
 * <ul>
 *   <li>实现 {@code common.ErrorCode} SPI（与其它模块统一手法）</li>
 *   <li>category 与 httpStatus 由 ErrorCategory 默认映射（INTERNAL → 500 等）</li>
 *   <li>messageKey 默认为 code</li>
 * </ul>
 */
public enum AgentErrorCode implements ErrorCode {

    /** section 渲染失败 */
    AGENT_PROMPT_ASSEMBLY_FAILED(ErrorCategory.INTERNAL),

    /** SKILL.md frontmatter 不合法 */
    AGENT_SKILL_LOAD_INVALID(ErrorCategory.VALIDATION),

    /** skill 工具调用时 skill 不存在 */
    AGENT_SKILL_NOT_FOUND(ErrorCategory.NOT_FOUND),

    /** 召回通道失败（非致命，已降级） */
    AGENT_MEMORY_RECALL_FAILED(ErrorCategory.DEPENDENCY),

    /** session memory 提取失败（计入断路器） */
    AGENT_SESSION_MEMORY_EXTRACTION_FAILED(ErrorCategory.DEPENDENCY),

    /** subagent 跑超阈值 */
    AGENT_SUBAGENT_TIMEOUT(ErrorCategory.DEPENDENCY);

    private final ErrorCategory category;

    AgentErrorCode(ErrorCategory category) {
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

    @Override
    public HttpStatus httpStatus() {
        return category.defaultHttpStatus();
    }
}