CREATE TABLE vision_analysis_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    running_count INT NOT NULL DEFAULT 0,
    succeeded_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_vision_job_package (package_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vision_analysis_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    target_image_id BIGINT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    analysis_generation BIGINT NOT NULL DEFAULT 0,
    run_epoch BIGINT NOT NULL DEFAULT 0,
    heartbeat_at DATETIME(3) NULL,
    provider_attempt_count INT NOT NULL DEFAULT 0,
    structure_round_count INT NOT NULL DEFAULT 0,
    last_request_id VARCHAR(128) NULL,
    fact_start_version BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(128) NULL,
    operational_metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_vision_item_scope (package_id, sku_id, scope, target_image_id),
    KEY idx_vision_item_recovery (status, heartbeat_at),
    KEY idx_vision_item_pending (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_visual_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    facts_json JSON NULL,
    version BIGINT NOT NULL DEFAULT 0,
    last_writer VARCHAR(32) NULL,
    operational_metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_asset_visual_analysis_sku (package_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_image_visual_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    image_id BIGINT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    facts_json JSON NULL,
    version BIGINT NOT NULL DEFAULT 0,
    operational_metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_asset_image_visual_analysis (package_id, sku_id, image_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
