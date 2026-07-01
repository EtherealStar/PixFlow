CREATE TABLE rubrics_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_count INT NOT NULL DEFAULT 0,
  succeeded_count INT NOT NULL DEFAULT 0,
  isolated_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  error_msg VARCHAR(1000) DEFAULT NULL,
  started_at DATETIME(3) DEFAULT NULL,
  finished_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_rubrics_run_template_created (template_id, created_at),
  KEY idx_rubrics_run_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_run_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  result_id BIGINT NOT NULL,
  task_id BIGINT DEFAULT NULL,
  sku_id VARCHAR(128) DEFAULT NULL,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  error_msg VARCHAR(1000) DEFAULT NULL,
  started_at DATETIME(3) DEFAULT NULL,
  finished_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_run_item_run_result (run_id, result_id),
  KEY idx_rubrics_run_item_run_status (run_id, status),
  KEY idx_rubrics_run_item_result (result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_score (
  id BIGINT NOT NULL AUTO_INCREMENT,
  result_id BIGINT NOT NULL,
  task_id BIGINT DEFAULT NULL,
  run_id BIGINT NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  overall_score DECIMAL(6, 2) NOT NULL,
  image_score DECIMAL(6, 2) DEFAULT NULL,
  copy_score DECIMAL(6, 2) DEFAULT NULL,
  decision_score DECIMAL(6, 2) DEFAULT NULL,
  dimension_scores_json JSON NOT NULL,
  explanation_json JSON,
  alert_flag BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_score_result (result_id),
  KEY idx_rubrics_score_task (task_id),
  KEY idx_rubrics_score_run (run_id),
  KEY idx_rubrics_score_template_created (template_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_baseline (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  run_id BIGINT NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_rubrics_baseline_template_active (template_id, active),
  KEY idx_rubrics_baseline_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_alert (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  baseline_run_id BIGINT DEFAULT NULL,
  template_id VARCHAR(128) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  overall_delta DECIMAL(6, 2) DEFAULT NULL,
  degraded_dimensions_json JSON NOT NULL,
  acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acknowledged_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rubrics_alert_run (run_id),
  KEY idx_rubrics_alert_template_ack (template_id, acknowledged)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
