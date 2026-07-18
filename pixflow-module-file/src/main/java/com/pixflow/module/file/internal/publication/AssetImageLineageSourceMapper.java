package com.pixflow.module.file.internal.publication;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetImageLineageSourceMapper extends BaseMapper<AssetImageLineageSource> {
    @Select("""
            select * from asset_image_lineage_source
            where asset_image_id = #{imageId} order by ordinal asc
            """)
    List<AssetImageLineageSource> findByImageId(@Param("imageId") long imageId);
}
