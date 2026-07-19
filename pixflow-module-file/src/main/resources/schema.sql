CREATE TABLE IF NOT EXISTS asset_package (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    file_hash VARCHAR(64),
    minio_zip_key VARCHAR(512),
    doc_key VARCHAR(512),
    archive_format VARCHAR(16),
    cleanup_status VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    image_count INT,
    extracted_count INT,
    error_summary VARCHAR(2000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_asset_package_file_hash ON asset_package (file_hash);

CREATE TABLE IF NOT EXISTS asset_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128),
    group_key VARCHAR(128),
    view_id VARCHAR(128),
    minio_key VARCHAR(512),
    original_path VARCHAR(512),
    display_name VARCHAR(255),
    source_type VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL',
    publication_status VARCHAR(16) NOT NULL DEFAULT 'READY',
    candidate_bucket VARCHAR(32),
    candidate_key VARCHAR(512),
    stable_bucket VARCHAR(32),
    content_type VARCHAR(128),
    byte_size BIGINT,
    source_task_id BIGINT,
    source_result_id BIGINT,
    source_unit_key VARCHAR(512),
    source_run_epoch BIGINT,
    source_image_id VARCHAR(64),
    producer_kind VARCHAR(32),
    producer_provider VARCHAR(128),
    producer_model VARCHAR(128),
    producer_tool VARCHAR(128),
    producer_node_id VARCHAR(128),
    publication_error VARCHAR(1000),
    publication_updated_at TIMESTAMP NULL,
    ready_at TIMESTAMP NULL,
    cleanup_status VARCHAR(32),
    cleanup_attempt_count INT NOT NULL DEFAULT 0,
    cleanup_last_error VARCHAR(1000),
    deletion_status VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_asset_image_package_path UNIQUE (package_id, original_path),
    CONSTRAINT uq_asset_image_source_result UNIQUE (source_task_id, source_result_id)
);
CREATE INDEX idx_asset_image_package_visible ON asset_image (package_id, deletion_status, id);
CREATE INDEX idx_asset_image_publication_recovery
    ON asset_image (publication_status, publication_updated_at);
CREATE INDEX idx_asset_image_cleanup_recovery
    ON asset_image (cleanup_status, publication_updated_at);

CREATE TABLE IF NOT EXISTS asset_reference_tombstone (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reference_kind VARCHAR(16) NOT NULL,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL DEFAULT '',
    image_id BIGINT NOT NULL DEFAULT 0,
    display_name VARCHAR(512) NOT NULL,
    CONSTRAINT uq_asset_reference_tombstone_identity
        UNIQUE (reference_kind, package_id, sku_id, image_id)
);

CREATE TABLE IF NOT EXISTS asset_cleanup_intent (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reference_kind VARCHAR(16) NOT NULL,
    package_id BIGINT NOT NULL,
    image_id BIGINT NOT NULL DEFAULT 0,
    storage_bucket VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    prefix_cleanup BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_asset_cleanup_intent_identity
        UNIQUE (reference_kind, package_id, image_id)
);
CREATE INDEX idx_asset_cleanup_intent_status ON asset_cleanup_intent (status, id);

CREATE TABLE IF NOT EXISTS asset_image_lineage_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_image_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    source_image_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_asset_image_lineage_ordinal UNIQUE (asset_image_id, ordinal),
    CONSTRAINT uq_asset_image_lineage_source UNIQUE (asset_image_id, source_image_id)
);

CREATE TABLE IF NOT EXISTS asset_copy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128),
    product_name VARCHAR(255),
    keywords VARCHAR(1000),
    description VARCHAR(4000),
    CONSTRAINT uk_asset_copy_package_sku UNIQUE (package_id, sku_id)
);

CREATE TABLE IF NOT EXISTS asset_ingest_error (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    original_path VARCHAR(512),
    stage VARCHAR(64) NOT NULL,
    code VARCHAR(128) NOT NULL,
    message VARCHAR(2000),
    created_at TIMESTAMP NOT NULL
);
