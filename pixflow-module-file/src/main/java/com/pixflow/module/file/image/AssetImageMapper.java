package com.pixflow.module.file.image;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.Instant;
import java.util.List;

@Mapper
public interface AssetImageMapper extends BaseMapper<AssetImage> {
    @Select("""
            select * from asset_image
            where source_task_id = #{taskId} and source_result_id = #{resultId}
            limit 1
            """)
    AssetImage findBySourceResult(@Param("taskId") long taskId,
                                  @Param("resultId") long resultId);

    @Select("""
            select * from asset_image
            where publication_status = 'PUBLISHING'
              and publication_updated_at < #{before}
            order by publication_updated_at, id
            limit #{limit}
            """)
    List<AssetImage> findPublishingBefore(@Param("before") Instant before,
                                          @Param("limit") int limit);

    @Select("""
            select * from asset_image
            where publication_status = 'READY'
              and cleanup_status = 'CLEANUP_PENDING'
              and publication_updated_at < #{before}
            order by publication_updated_at, id
            limit #{limit}
            """)
    List<AssetImage> findCleanupPendingBefore(@Param("before") Instant before,
                                              @Param("limit") int limit);

    @Update("""
            update asset_image
            set publication_updated_at = #{claimedAt}
            where id = #{imageId} and publication_status = 'PUBLISHING'
              and publication_updated_at = #{observedAt}
            """)
    int claimPublishing(@Param("imageId") long imageId,
                        @Param("observedAt") Instant observedAt,
                        @Param("claimedAt") Instant claimedAt);

    @Update("""
            update asset_image
            set publication_updated_at = #{claimedAt},
                cleanup_attempt_count = cleanup_attempt_count + 1
            where id = #{imageId} and publication_status = 'READY'
              and cleanup_status = 'CLEANUP_PENDING'
              and publication_updated_at = #{observedAt}
            """)
    int claimCleanup(@Param("imageId") long imageId,
                     @Param("observedAt") Instant observedAt,
                     @Param("claimedAt") Instant claimedAt);

    @Update("""
            update asset_image
            set publication_status = 'READY', minio_key = #{stableKey}, content_hash = #{contentHash},
                publication_error = null, publication_updated_at = #{now},
                ready_at = #{now}, cleanup_status = 'CLEANUP_PENDING', updated_at = #{now}
            where id = #{imageId} and publication_status = 'PUBLISHING'
            """)
    int finalizeReady(@Param("imageId") long imageId, @Param("stableKey") String stableKey,
                      @Param("contentHash") String contentHash,
                      @Param("now") Instant now);

    @Update("""
            update asset_image
            set cleanup_status = 'CLEANED', cleanup_last_error = null,
                publication_updated_at = #{now}
            where id = #{imageId} and publication_status = 'READY'
            """)
    int markCleaned(@Param("imageId") long imageId, @Param("now") Instant now);

    @Update("""
            update asset_image
            set cleanup_last_error = #{error}, publication_updated_at = #{now}
            where id = #{imageId} and publication_status = 'READY'
              and cleanup_status = 'CLEANUP_PENDING'
            """)
    int recordCleanupError(@Param("imageId") long imageId,
                           @Param("error") String error, @Param("now") Instant now);

    @Update("""
            update asset_image
            set publication_error = #{error}, publication_updated_at = #{now}
            where id = #{imageId} and publication_status = 'PUBLISHING'
            """)
    int recordPublicationError(@Param("imageId") long imageId,
                               @Param("error") String error, @Param("now") Instant now);

    @Select("""
            <script>
            select distinct sku_id from asset_image
            where package_id = #{packageId}
              and source_type = 'ORIGINAL' and publication_status = 'READY'
              and deletion_status is null and sku_id is not null and sku_id != ''
            <if test="excludedSkuIds != null and !excludedSkuIds.isEmpty()">
              and sku_id not in
              <foreach collection="excludedSkuIds" item="sku" open="(" separator="," close=")">
                #{sku}
              </foreach>
            </if>
            order by sku_id
            limit #{limit} offset #{offset}
            </script>
            """)
    List<String> listReadyOriginalSkus(@Param("packageId") long packageId,
                                       @Param("excludedSkuIds") List<String> excludedSkuIds,
                                       @Param("offset") long offset,
                                       @Param("limit") long limit);

    @Select("""
            <script>
            select count(distinct sku_id) from asset_image
            where package_id = #{packageId}
              and source_type = 'ORIGINAL' and publication_status = 'READY'
              and deletion_status is null and sku_id is not null and sku_id != ''
            <if test="excludedSkuIds != null and !excludedSkuIds.isEmpty()">
              and sku_id not in
              <foreach collection="excludedSkuIds" item="sku" open="(" separator="," close=")">
                #{sku}
              </foreach>
            </if>
            </script>
            """)
    long countReadyOriginalSkus(@Param("packageId") long packageId,
                                @Param("excludedSkuIds") List<String> excludedSkuIds);
}
