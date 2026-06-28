CREATE TABLE IF NOT EXISTS commerce_data (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id VARCHAR(128) NOT NULL,
  category VARCHAR(128) NOT NULL,
  impressions BIGINT NOT NULL,
  ctr DECIMAL(10,6) NOT NULL,
  add_cart_rate DECIMAL(10,6) NOT NULL,
  purchase_rate DECIMAL(10,6) NOT NULL,
  period_type VARCHAR(16) NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  source VARCHAR(128) NOT NULL,
  fetched_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_commerce_natural (sku_id, period_type, period_start, source),
  KEY idx_category_period (category, period_type, period_start),
  KEY idx_sku_period (sku_id, period_start)
);

CREATE TABLE IF NOT EXISTS commerce_import_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source VARCHAR(64) NOT NULL,
  platform VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  sku_count INT NOT NULL,
  succeeded_count INT NOT NULL,
  failed_count INT NOT NULL,
  request_json TEXT,
  report_json TEXT,
  error_summary VARCHAR(1000),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6)
);
