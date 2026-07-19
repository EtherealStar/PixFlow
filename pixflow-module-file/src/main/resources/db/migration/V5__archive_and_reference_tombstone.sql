ALTER TABLE asset_package
  ADD COLUMN archive_format VARCHAR(16) NULL AFTER doc_key,
  ADD COLUMN cleanup_status VARCHAR(32) NULL AFTER archive_format;

UPDATE asset_package
SET archive_format = 'ZIP'
WHERE archive_format IS NULL AND minio_zip_key IS NOT NULL;

ALTER TABLE asset_image
  ADD COLUMN deletion_status VARCHAR(32) NULL AFTER cleanup_last_error;

CREATE TABLE asset_reference_tombstone (
  id BIGINT NOT NULL AUTO_INCREMENT,
  reference_kind VARCHAR(16) NOT NULL,
  package_id BIGINT NOT NULL,
  sku_id VARCHAR(128) NOT NULL DEFAULT '',
  image_id BIGINT NOT NULL DEFAULT 0,
  display_name VARCHAR(512) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_asset_reference_tombstone_identity
    (reference_kind, package_id, sku_id, image_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_cleanup_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  reference_kind VARCHAR(16) NOT NULL,
  package_id BIGINT NOT NULL,
  image_id BIGINT NOT NULL DEFAULT 0,
  storage_bucket VARCHAR(32) NOT NULL,
  storage_key VARCHAR(512) NOT NULL,
  prefix_cleanup BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_asset_cleanup_intent_identity (reference_kind, package_id, image_id),
  KEY idx_asset_cleanup_intent_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
