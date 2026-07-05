ALTER TABLE process_result
  ADD COLUMN display_name VARCHAR(255) NULL,
  ADD COLUMN deleted_at DATETIME(3) NULL;

CREATE INDEX idx_process_result_task_visible
  ON process_result (task_id, deleted_at, id);
