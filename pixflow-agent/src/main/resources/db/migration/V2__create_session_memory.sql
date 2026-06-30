-- Session Memory 表
-- 对应 agent.md §7.2.1：单行 per session；7 字段 + 索引
-- 本期由 SessionMemoryService 维护（实现 context.SessionMemoryPort SPI）。

CREATE TABLE IF NOT EXISTS session_memory (
    conversation_id      VARCHAR(64)  NOT NULL,
    content              MEDIUMTEXT   NOT NULL,
    last_summarized_seq  BIGINT       NOT NULL DEFAULT 0,
    covered_turn_count   INT          NOT NULL DEFAULT 0,
    source               VARCHAR(16)  NOT NULL,
    content_hash         VARCHAR(64)  NOT NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    PRIMARY KEY (conversation_id),
    KEY idx_session_memory_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
