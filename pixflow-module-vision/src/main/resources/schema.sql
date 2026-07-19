CREATE TABLE IF NOT EXISTS vision_analysis_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    running_count INT NOT NULL DEFAULT 0,
    succeeded_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_vision_job_package UNIQUE (package_id)
);

CREATE TABLE IF NOT EXISTS vision_analysis_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    target_image_id BIGINT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    analysis_generation BIGINT NOT NULL DEFAULT 0,
    run_epoch BIGINT NOT NULL DEFAULT 0,
    heartbeat_at TIMESTAMP NULL,
    provider_attempt_count INT NOT NULL DEFAULT 0,
    structure_round_count INT NOT NULL DEFAULT 0,
    last_request_id VARCHAR(128),
    fact_start_version BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(128),
    operational_metadata JSON,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_vision_item_scope UNIQUE (
        package_id, sku_id, scope, target_image_id
    )
);
CREATE INDEX idx_vision_item_recovery
    ON vision_analysis_item (status, heartbeat_at);
CREATE INDEX idx_vision_item_pending
    ON vision_analysis_item (status, updated_at);

CREATE TABLE IF NOT EXISTS asset_visual_analysis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    facts_json JSON,
    version BIGINT NOT NULL DEFAULT 0,
    last_writer VARCHAR(32),
    operational_metadata JSON,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_asset_visual_analysis_sku UNIQUE (package_id, sku_id)
);

CREATE TABLE IF NOT EXISTS asset_image_visual_analysis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    image_id BIGINT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    facts_json JSON,
    version BIGINT NOT NULL DEFAULT 0,
    operational_metadata JSON,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_asset_image_visual_analysis UNIQUE (
        package_id, sku_id, image_id
    )
);
