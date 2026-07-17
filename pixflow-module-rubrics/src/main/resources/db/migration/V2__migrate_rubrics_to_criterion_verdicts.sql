ALTER TABLE rubrics_run
  ADD COLUMN template_hash CHAR(64) NULL,
  ADD COLUMN evaluator_version VARCHAR(255) NULL,
  ADD COLUMN subject_type VARCHAR(32) NULL,
  ADD COLUMN dataset_id VARCHAR(128) NULL,
  ADD COLUMN dataset_version VARCHAR(64) NULL,
  ADD COLUMN stats_json JSON NULL;

ALTER TABLE rubrics_run_item
  MODIFY result_id BIGINT NULL,
  ADD COLUMN subject_type VARCHAR(32) NULL,
  ADD COLUMN subject_id VARCHAR(255) NULL,
  ADD COLUMN subject_snapshot_hash CHAR(64) NULL,
  ADD COLUMN quality_gate VARCHAR(32) NULL,
  ADD COLUMN pass_rate DECIMAL(8,6) NULL,
  ADD COLUMN coverage DECIMAL(8,6) NULL,
  ADD COLUMN evidence_pack_hash CHAR(64) NULL,
  ADD UNIQUE KEY uk_rubrics_run_item_subject (run_id, subject_type, subject_id);

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
  prompt_hash CHAR(64) NOT NULL,
  latency_ms BIGINT NOT NULL,
  usage_json JSON NOT NULL,
  error_code VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_judge_rollout (criterion_result_id, rollout_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_dataset (
  id BIGINT NOT NULL AUTO_INCREMENT, dataset_id VARCHAR(128) NOT NULL, version VARCHAR(64) NOT NULL,
  subject_type VARCHAR(32) NOT NULL, description VARCHAR(1000) NULL, manifest_hash CHAR(64) NOT NULL,
  gold_label_version VARCHAR(64) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_rubrics_dataset (dataset_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_dataset_item (
  id BIGINT NOT NULL AUTO_INCREMENT, dataset_pk BIGINT NOT NULL, subject_id VARCHAR(255) NOT NULL,
  subject_snapshot_hash CHAR(64) NOT NULL, evidence_pack_hash CHAR(64) NULL, replayable BOOLEAN NOT NULL DEFAULT TRUE,
  replay_error VARCHAR(500) NULL, PRIMARY KEY (id), UNIQUE KEY uk_rubrics_dataset_item (dataset_pk, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_gold_label (
  id BIGINT NOT NULL AUTO_INCREMENT, dataset_item_id BIGINT NOT NULL, criterion_key VARCHAR(128) NOT NULL,
  annotator_id VARCHAR(128) NOT NULL, verdict VARCHAR(32) NOT NULL, adjudicated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (id),
  UNIQUE KEY uk_rubrics_gold_label (dataset_item_id, criterion_key, annotator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_validation_report (
  id BIGINT NOT NULL AUTO_INCREMENT, dataset_pk BIGINT NOT NULL, template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL, evaluator_version VARCHAR(255) NOT NULL, report_json JSON NOT NULL,
  thresholds_met BOOLEAN NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubrics_promotion (
  id BIGINT NOT NULL AUTO_INCREMENT, evaluation_id BIGINT NOT NULL, criterion_key VARCHAR(128) NOT NULL,
  reviewer_id VARCHAR(128) NOT NULL, approved_text TEXT NOT NULL, provenance_json JSON NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, destination_memory_id BIGINT NULL,
  error_msg VARCHAR(1000) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_rubrics_promotion_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE rubrics_baseline
  ADD COLUMN dataset_id VARCHAR(128) NULL, ADD COLUMN dataset_version VARCHAR(64) NULL,
  ADD COLUMN template_hash CHAR(64) NULL, ADD COLUMN evaluator_version VARCHAR(255) NULL,
  ADD COLUMN details_json JSON NULL;

ALTER TABLE rubrics_alert
  ADD COLUMN dataset_id VARCHAR(128) NULL, ADD COLUMN dataset_version VARCHAR(64) NULL,
  ADD COLUMN template_version VARCHAR(64) NULL, ADD COLUMN evaluator_version VARCHAR(255) NULL,
  ADD COLUMN criterion_details_json JSON NULL;
