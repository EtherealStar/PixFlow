package com.pixflow.module.vision.enrich;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetCopyWriteMapper {
    @Select("""
            SELECT package_id AS packageId, sku_id AS skuId, product_name AS productName,
                   keywords, description
            FROM asset_copy
            WHERE package_id = #{packageId} AND sku_id = #{skuId}
            LIMIT 1
            """)
    AssetCopyRow find(long packageId, String skuId);

    @Insert("""
            INSERT INTO asset_copy(package_id, sku_id, product_name, keywords, description)
            VALUES(#{packageId}, #{skuId}, #{draft.productName}, #{draft.keywords}, #{draft.description})
            ON DUPLICATE KEY UPDATE
              product_name = COALESCE(NULLIF(product_name, ''), VALUES(product_name)),
              keywords = COALESCE(NULLIF(keywords, ''), VALUES(keywords)),
              description = COALESCE(NULLIF(description, ''), VALUES(description))
            """)
    int upsertGapOnly(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("draft") ProductCopyDraft draft);
}
