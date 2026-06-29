package com.pixflow.module.vision.enrich;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetImageReadMapper {
    @Select("""
            SELECT id, package_id AS packageId, sku_id AS skuId, view_id AS viewId, minio_key AS minioKey
            FROM asset_image
            WHERE package_id = #{packageId}
              AND sku_id IS NOT NULL
              AND minio_key IS NOT NULL
            ORDER BY sku_id, view_id, id
            """)
    List<AssetImageRow> findByPackageId(long packageId);
}
