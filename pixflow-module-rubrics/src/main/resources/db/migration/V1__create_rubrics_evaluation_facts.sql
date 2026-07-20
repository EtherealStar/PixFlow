CREATE TABLE rubrics_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  template_hash CHAR(64) NOT NULL,
  evaluator_version VARCHAR(255) NULL,
  subject_type VARCHAR(32) NOT NULL,
  dataset_id VARCHAR(128) NULL,
  dataset_version VARCHAR(64) NULL,
  baseline_run_id BIGINT NULL,
  purpose VARCHAR(32) NOT NULL DEFAULT 'MANUAL_INSPECTION',
  trigger_type VARCHAR(32) NOT NULL,
  admission_key VARCHAR(255) NULL,
  status VARCHAR(32) NOT NULL,
  total_count INT NOT NULL DEFAULT 0,
  succeeded_count INT NOT NULL DEFAULT 0,
  isolated_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  stats_json JSON NULL,
  error_msg VARCHAR(1000) NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_run_admission (admission_key),
  KEY idx_rubrics_run_status_created (status, created_at),
  KEY idx_rubrics_run_template_created (template_id, template_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_run_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  subject_type VARCHAR(32) NOT NULL,
  subject_id VARCHAR(255) NOT NULL,
  subject_snapshot_hash CHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  claim_epoch BIGINT NOT NULL DEFAULT 0,
  claim_owner VARCHAR(128) NULL,
  lease_expires_at DATETIME(3) NULL,
  heartbeat_at DATETIME(3) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  quality_gate VARCHAR(32) NULL,
  pass_rate DECIMAL(8,6) NULL,
  coverage DECIMAL(8,6) NULL,
  evidence_pack_hash CHAR(64) NULL,
  error_msg VARCHAR(1000) NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_run_item_subject (run_id, subject_type, subject_id),
  KEY idx_rubrics_run_item_recovery (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_evaluation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  subject_type VARCHAR(32) NOT NULL,
  subject_id VARCHAR(255) NOT NULL,
  subject_snapshot_hash CHAR(64) NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  template_hash CHAR(64) NOT NULL,
  evaluator_version VARCHAR(255) NOT NULL,
  evidence_pack_hash CHAR(64) NOT NULL,
  evidence_json JSON NOT NULL,
  quality_gate VARCHAR(32) NOT NULL,
  pass_rate DECIMAL(8,6) NULL,
  coverage DECIMAL(8,6) NULL,
  summary_json JSON NOT NULL,
  self_judged BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_evaluation_run_subject (run_id, subject_type, subject_id),
  KEY idx_rubrics_evaluation_subject (subject_type, subject_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_criterion_result (
  id BIGINT NOT NULL AUTO_INCREMENT,
  evaluation_id BIGINT NOT NULL,
  criterion_key VARCHAR(128) NOT NULL,
  criterion_kind VARCHAR(32) NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  rationale TEXT NOT NULL,
  evidence_ids_json JSON NOT NULL,
  diagnostics_json JSON NOT NULL,
  agreement DECIMAL(8,6) NULL,
  rollout_count INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_criterion_result (evaluation_id, criterion_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_judge_rollout (
  id BIGINT NOT NULL AUTO_INCREMENT,
  criterion_result_id BIGINT NOT NULL,
  rollout_index INT NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  rationale TEXT NOT NULL,
  evidence_ids_json JSON NOT NULL,
  provider VARCHAR(64) NOT NULL,
  model VARCHAR(128) NOT NULL,
  model_revision VARCHAR(128) NULL,
  prompt_hash CHAR(64) NOT NULL,
  latency_ms BIGINT NOT NULL,
  usage_json JSON NOT NULL,
  error_code VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_judge_rollout (criterion_result_id, rollout_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_dataset (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dataset_id VARCHAR(128) NOT NULL,
  version VARCHAR(64) NOT NULL,
  subject_type VARCHAR(32) NOT NULL,
  description VARCHAR(1000) NULL,
  manifest_hash CHAR(64) NOT NULL,
  holdout_manifest_hash CHAR(64) NULL,
  gold_label_version VARCHAR(64) NULL,
  evidence_schema_version VARCHAR(64) NOT NULL DEFAULT '1',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_dataset (dataset_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_dataset_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dataset_pk BIGINT NOT NULL,
  subject_id VARCHAR(255) NOT NULL,
  subject_snapshot_hash CHAR(64) NOT NULL,
  partition_name VARCHAR(32) NOT NULL,
  category_name VARCHAR(128) NOT NULL DEFAULT 'UNSPECIFIED',
  difficulty VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED',
  replayable BOOLEAN NOT NULL DEFAULT TRUE,
  replay_error VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_dataset_item (dataset_pk, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_gold_label (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dataset_item_id BIGINT NOT NULL,
  criterion_key VARCHAR(128) NOT NULL,
  annotator_id VARCHAR(128) NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  adjudicated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_gold_label (dataset_item_id, criterion_key, annotator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_validation_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  dataset_pk BIGINT NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  template_hash CHAR(64) NOT NULL,
  evaluator_version VARCHAR(255) NOT NULL,
  evidence_schema_version VARCHAR(64) NOT NULL,
  report_json JSON NOT NULL,
  thresholds_met BOOLEAN NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_validation_run (run_id),
  KEY idx_rubrics_validation_release (template_id, template_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_alert (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  baseline_run_id BIGINT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  dataset_id VARCHAR(128) NOT NULL,
  dataset_version VARCHAR(64) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  criterion_details_json JSON NOT NULL,
  acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acknowledged_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_alert_run (run_id),
  KEY idx_rubrics_alert_acknowledged (acknowledged, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
