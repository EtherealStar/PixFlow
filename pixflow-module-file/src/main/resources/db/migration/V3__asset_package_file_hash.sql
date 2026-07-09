ALTER TABLE asset_package
  ADD COLUMN file_hash VARCHAR(64) NULL AFTER name;

CREATE UNIQUE INDEX uk_asset_package_file_hash
  ON asset_package (file_hash);
