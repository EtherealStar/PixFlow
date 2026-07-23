CREATE TABLE IF NOT EXISTS user_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `key` VARCHAR(128) NOT NULL,
    value VARCHAR(4000) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_preference_key UNIQUE (`key`)
);

CREATE TABLE IF NOT EXISTS sku_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128),
    params_json JSON,
    metrics_before JSON,
    metrics_after JSON,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_sku_history_sku_created (sku_id, created_at)
);

CREATE TABLE IF NOT EXISTS analysis_insight (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    text TEXT NOT NULL,
    category VARCHAR(128),
    source VARCHAR(128),
    confidence DOUBLE NOT NULL DEFAULT 0.5,
    related_sku VARCHAR(128),
    content_hash CHAR(32) NOT NULL,
    importance DOUBLE NOT NULL DEFAULT 0.5,
    status VARCHAR(32) NOT NULL,
    access_count INT NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMP NULL,
    last_reinforced_at TIMESTAMP NULL,
    decay_score DOUBLE NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NULL,
    CONSTRAINT uk_analysis_insight_content_hash UNIQUE (content_hash),
    INDEX idx_analysis_insight_status_expires (status, expires_at),
    INDEX idx_analysis_insight_sku_status (related_sku, status),
    INDEX idx_analysis_insight_category_status (category, status),
    FULLTEXT INDEX ft_analysis_insight_text (text) WITH PARSER ngram
);
