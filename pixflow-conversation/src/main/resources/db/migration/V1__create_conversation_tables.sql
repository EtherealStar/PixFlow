CREATE TABLE conversation (
  id          VARCHAR(128) PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  title       VARCHAR(160) NULL,
  package_id  VARCHAR(128) NULL,
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY idx_conversation_owner_archived_updated (owner_user_id, archived, updated_at DESC),
  KEY idx_conversation_owner_updated (owner_user_id, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'conversation 会话表：message 明细由 harness/session 唯一写入';
