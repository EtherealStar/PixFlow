ALTER TABLE asset_image
  ADD COLUMN display_name VARCHAR(255) NULL,
  ADD COLUMN deleted_at DATETIME(3) NULL;

CREATE INDEX idx_asset_image_package_visible
  ON asset_image (package_id, deleted_at, id);
