-- pending_plan:dag 提案载体(对齐 dag.md §6.2)
-- 独立表不复用 process_task 状态机;对 IMAGE_PLAN / IMAGEGEN 两类提案中立。
CREATE TABLE pending_plan (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tool_call_id    VARCHAR(128) NOT NULL,
  conversation_id VARCHAR(128) NOT NULL,
  type            VARCHAR(32)  NOT NULL COMMENT 'IMAGE_PLAN | IMAGEGEN',
  dag_json        TEXT         NOT NULL COMMENT '规范化后的 DAG JSON',
  payload_hash    VARCHAR(64)  NOT NULL COMMENT 'sha256(canonicalJson(dag_json))',
  schema_version  VARCHAR(16)  NOT NULL COMMENT '入队时 dag schema 大版本号',
  note            VARCHAR(1024) NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING | CONFIRMED | DISCARDED | EXPIRED',
  created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at      TIMESTAMP(6) NOT NULL,
  confirmed_at    TIMESTAMP(6) NULL,
  task_id         VARCHAR(64)  NULL,
  UNIQUE KEY uk_tool_call (tool_call_id),
  KEY idx_conversation_status (conversation_id, status),
  KEY idx_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'dag 提案载体:用户确认后转 process_task';