package com.pixflow.module.file.internal.deletion;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetReferenceTombstoneMapper extends BaseMapper<AssetReferenceTombstone> {
    @Insert("""
            insert into asset_reference_tombstone
              (reference_kind, package_id, sku_id, image_id, display_name)
            values (#{kind}, #{packageId}, #{skuId}, #{imageId}, #{displayName})
            on duplicate key update display_name = display_name
            """)
    int insertIfAbsent(@Param("kind") String kind,
                       @Param("packageId") long packageId,
                       @Param("skuId") String skuId,
                       @Param("imageId") long imageId,
                       @Param("displayName") String displayName);

    @Select("""
            select display_name from asset_reference_tombstone
            where reference_kind = 'PACKAGE' and package_id = #{packageId}
              and sku_id = '' and image_id = 0
            limit 1
            """)
    String findPackageDisplayName(@Param("packageId") long packageId);

    @Select("""
            select * from asset_reference_tombstone
            where reference_kind = #{kind} and package_id = #{packageId}
              and sku_id = #{skuId} and image_id = #{imageId}
            limit 1
            """)
    AssetReferenceTombstone findIdentity(@Param("kind") String kind,
                                         @Param("packageId") long packageId,
                                         @Param("skuId") String skuId,
                                         @Param("imageId") long imageId);
}
