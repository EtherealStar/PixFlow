package com.pixflow.module.file.pkg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.Instant;

@Mapper
public interface AssetPackageMapper extends BaseMapper<AssetPackage> {
    @Update("""
            update asset_package
            set status = #{status}, error_summary = #{errorSummary}, updated_at = #{now}
            where id = #{packageId} and cleanup_status is null
              and status in ('UPLOADED', 'EXTRACTING')
            """)
    int finishExtraction(@Param("packageId") long packageId,
                         @Param("status") String status,
                         @Param("errorSummary") String errorSummary,
                         @Param("now") Instant now);
}
