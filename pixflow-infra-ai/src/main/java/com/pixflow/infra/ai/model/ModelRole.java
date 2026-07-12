package com.pixflow.infra.ai.model;

/**
 * 逻辑角色：上层只选职责，不锁死具体供应商型号。
 */
public enum ModelRole {
    PRIMARY_CHAT,
    VISION,
    RUBRICS_JUDGE_TEXT,
    RUBRICS_JUDGE_VISION,
    IMAGEGEN,
    EMBEDDING,
    RERANK
}
