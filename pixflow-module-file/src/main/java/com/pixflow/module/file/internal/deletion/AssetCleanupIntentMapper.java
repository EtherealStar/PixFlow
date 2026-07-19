package com.pixflow.module.file.internal.deletion;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssetCleanupIntentMapper extends BaseMapper<AssetCleanupIntent> {
    @Insert("""
            insert into asset_cleanup_intent
              (reference_kind, package_id, image_id, storage_bucket, storage_key,
               prefix_cleanup, status, attempt_count, created_at, updated_at)
            values (#{kind}, #{packageId}, #{imageId}, #{bucket}, #{key},
                    #{prefix}, 'PENDING', 0, #{now}, #{now})
            on duplicate key update updated_at = updated_at
            """)
    int insertIfAbsent(@Param("kind") String kind,
                       @Param("packageId") long packageId,
                       @Param("imageId") long imageId,
                       @Param("bucket") String bucket,
                       @Param("key") String key,
                       @Param("prefix") boolean prefix,
                       @Param("now") Instant now);

    @Select("""
            select * from asset_cleanup_intent
            where status = 'PENDING'
            order by updated_at, id
            limit #{limit}
            """)
    List<AssetCleanupIntent> findPending(@Param("limit") int limit);

    @Update("""
            update asset_cleanup_intent
            set attempt_count = attempt_count + 1, last_error = #{error}, updated_at = #{now}
            where id = #{id} and status = 'PENDING'
            """)
    int recordFailure(@Param("id") long id, @Param("error") String error,
                      @Param("now") Instant now);
}
