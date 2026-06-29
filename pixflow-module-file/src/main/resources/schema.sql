CREATE TABLE IF NOT EXISTS asset_package (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    minio_zip_key VARCHAR(512),
    doc_key VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    image_count INT,
    extracted_count INT,
    error_summary VARCHAR(2000),
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS asset_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_id BIGINT NOT NULL,
    sku_id VARCHAR(128),
    group_key VARCHAR(128),
    view_id VARCHAR(128),
    minio_key VARCHAR(512),
    original_path VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_asset_image_package_path UNIQUE (package_id, original_path)
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
