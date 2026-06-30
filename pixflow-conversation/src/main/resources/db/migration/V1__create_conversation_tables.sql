CREATE TABLE conversation (
  id          VARCHAR(128) PRIMARY KEY,
  title       VARCHAR(160) NULL,
  package_id  VARCHAR(128) NULL,
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP(6) NOT NULL,
  updated_at  TIMESTAMP(6) NOT NULL,
  KEY idx_conversation_archived_updated (archived, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'conversation 会话表：message 明细由 harness/session 唯一写入';
