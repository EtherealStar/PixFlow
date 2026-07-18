CREATE TABLE IF NOT EXISTS message (
  id                  VARCHAR(64) PRIMARY KEY,
  conversation_id     VARCHAR(64)  NOT NULL,
  seq                 BIGINT       NOT NULL,
  role                VARCHAR(32)  NOT NULL,
  content             MEDIUMTEXT   NULL,
  tool_call_id        VARCHAR(128) NULL,
  compaction_marker   VARCHAR(32)  NULL,
  metadata            JSON         NULL,
  task_id             VARCHAR(64)  NULL,
  created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_message_conversation_seq (conversation_id, seq),
  KEY idx_message_conversation_marker (conversation_id, compaction_marker),
  KEY idx_message_conversation_seq (conversation_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'session transcript append-only fact table';

CREATE TABLE IF NOT EXISTS message_compaction (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id     VARCHAR(64) NOT NULL,
  boundary_message_id VARCHAR(64) NOT NULL,
  summary_message_id  VARCHAR(64) NOT NULL,
  covered_up_to_seq   BIGINT      NOT NULL,
  compaction_trigger  VARCHAR(32) NOT NULL,
  metadata            JSON        NULL,
  created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_message_compaction_conversation_id (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'session destructive compaction epochs';
