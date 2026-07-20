package com.pixflow.module.vision.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.List;
import com.pixflow.module.vision.execution.VisionWorkItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VisionStateMapper {
    @Select("select id, package_id, sku_id, scope, target_image_id, input_fingerprint, status, "
            + "analysis_generation, run_epoch, fact_start_version, provider_attempt_count, "
            + "structure_round_count from vision_analysis_item where id = #{itemId}")
    VisionWorkItem findWorkItem(@Param("itemId") long itemId);

    @Select("select id, package_id, sku_id, scope, target_image_id, input_fingerprint, status, "
            + "analysis_generation, run_epoch, fact_start_version, provider_attempt_count, "
            + "structure_round_count from vision_analysis_item where id = #{itemId} for update")
    VisionWorkItem lockWorkItem(@Param("itemId") long itemId);

    @Insert("""
            insert ignore into vision_analysis_item (
                package_id, sku_id, scope, target_image_id, input_fingerprint,
                status, analysis_generation, run_epoch, provider_attempt_count,
                structure_round_count, fact_start_version, created_at, updated_at
            ) values (#{packageId}, #{skuId}, 'IMAGE', #{imageId}, #{fingerprint},
                      'PENDING', 1, 0, 0, 0, 0, #{now}, #{now})
            """)
    int ensureImageItem(@Param("packageId") long packageId, @Param("skuId") String skuId,
                        @Param("imageId") long imageId, @Param("fingerprint") String fingerprint,
                        @Param("now") Instant now);

    @Select("select id, package_id, sku_id, scope, target_image_id, input_fingerprint, status, "
            + "analysis_generation, run_epoch, fact_start_version, provider_attempt_count, "
            + "structure_round_count from vision_analysis_item where package_id = #{packageId} "
            + "and sku_id = #{skuId} and scope = 'IMAGE' and target_image_id = #{imageId} for update")
    VisionWorkItem lockImageWorkItem(@Param("packageId") long packageId, @Param("skuId") String skuId,
                                     @Param("imageId") long imageId);

    @Select("select input_fingerprint, facts_json, version from asset_image_visual_analysis "
            + "where package_id = #{packageId} and sku_id = #{skuId} and image_id = #{imageId}")
    ImageFactsRow findImageFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                                 @Param("imageId") long imageId);

    @Update("update asset_image_visual_analysis set input_fingerprint = #{fingerprint}, facts_json = null, "
            + "version = version + 1, operational_metadata = null, updated_at = #{now} "
            + "where package_id = #{packageId} and sku_id = #{skuId} and image_id = #{imageId} "
            + "and input_fingerprint <> #{fingerprint}")
    int invalidateImageFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                             @Param("imageId") long imageId, @Param("fingerprint") String fingerprint,
                             @Param("now") Instant now);

    @Update("""
            update vision_analysis_item set status = 'RUNNING', run_epoch = run_epoch + 1,
                   heartbeat_at = #{now}, failure_code = null, updated_at = #{now}
             where id = #{itemId} and analysis_generation = #{generation}
               and status in ('PENDING', 'EXPIRED')
            """)
    int claimWorkItem(@Param("itemId") long itemId, @Param("generation") long generation,
                      @Param("now") Instant now);

    @Update("""
            update vision_analysis_item
               set provider_attempt_count = provider_attempt_count + 1, updated_at = #{now}
             where id = #{itemId} and status = 'RUNNING'
               and analysis_generation = #{generation} and run_epoch = #{epoch}
               and provider_attempt_count < 3
            """)
    int reserveProviderAttempt(@Param("itemId") long itemId, @Param("generation") long generation,
                               @Param("epoch") long epoch, @Param("now") Instant now);

    @Update("""
            update vision_analysis_item set structure_round_count = #{round}, updated_at = #{now}
             where id = #{itemId} and status = 'RUNNING'
               and analysis_generation = #{generation} and run_epoch = #{epoch}
               and structure_round_count < #{round}
            """)
    int recordStructureRound(@Param("itemId") long itemId, @Param("generation") long generation,
                             @Param("epoch") long epoch, @Param("round") int round,
                             @Param("now") Instant now);

    @Update("""
            update vision_analysis_item set heartbeat_at = #{now}, updated_at = #{now}
             where id = #{itemId} and status = 'RUNNING'
               and analysis_generation = #{generation} and run_epoch = #{epoch}
            """)
    int heartbeat(@Param("itemId") long itemId, @Param("generation") long generation,
                  @Param("epoch") long epoch, @Param("now") Instant now);

    @Insert("""
            insert ignore into asset_visual_analysis (
                package_id, sku_id, input_fingerprint, facts_json, version, last_writer,
                operational_metadata, created_at, updated_at
            ) values (#{packageId}, #{skuId}, #{fingerprint}, #{factsJson}, 1, 'AI_GENERATED',
                      cast(#{metadataJson} as json), #{now}, #{now})
            """)
    int insertAiSkuFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                         @Param("fingerprint") String fingerprint,
                         @Param("factsJson") String factsJson,
                         @Param("metadataJson") String metadataJson, @Param("now") Instant now);

    @Update("""
            update asset_visual_analysis
               set input_fingerprint = #{fingerprint}, facts_json = #{factsJson},
                   version = version + 1, last_writer = 'AI_GENERATED',
                   operational_metadata = cast(#{metadataJson} as json), updated_at = #{now}
             where package_id = #{packageId} and sku_id = #{skuId}
               and version = #{expectedVersion}
            """)
    int updateAiSkuFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                         @Param("fingerprint") String fingerprint,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("factsJson") String factsJson,
                         @Param("metadataJson") String metadataJson, @Param("now") Instant now);

    @Insert("""
            insert ignore into asset_image_visual_analysis (
                package_id, sku_id, image_id, input_fingerprint, facts_json, version,
                operational_metadata, created_at, updated_at
            ) values (#{packageId}, #{skuId}, #{imageId}, #{fingerprint}, #{factsJson}, 1,
                      cast(#{metadataJson} as json), #{now}, #{now})
            """)
    int insertAiImageFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                           @Param("imageId") long imageId, @Param("fingerprint") String fingerprint,
                           @Param("factsJson") String factsJson, @Param("metadataJson") String metadataJson,
                           @Param("now") Instant now);

    @Update("""
            update asset_image_visual_analysis
               set input_fingerprint = #{fingerprint}, facts_json = #{factsJson},
                   version = version + 1, operational_metadata = cast(#{metadataJson} as json),
                   updated_at = #{now}
             where package_id = #{packageId} and sku_id = #{skuId} and image_id = #{imageId}
               and version = #{expectedVersion}
            """)
    int updateAiImageFacts(@Param("packageId") long packageId, @Param("skuId") String skuId,
                           @Param("imageId") long imageId, @Param("fingerprint") String fingerprint,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("factsJson") String factsJson, @Param("metadataJson") String metadataJson,
                           @Param("now") Instant now);

    @Update("""
            update vision_analysis_job j
              join (
                select package_id, count(*) total_count,
                       sum(status in ('PENDING', 'EXPIRED')) pending_count,
                       sum(status = 'RUNNING') running_count,
                       sum(status = 'SUCCESS') succeeded_count,
                       sum(status = 'FAILED') failed_count
                  from vision_analysis_item where package_id = #{packageId}
                 group by package_id
              ) c on c.package_id = j.package_id
               set j.total_count = c.total_count, j.pending_count = c.pending_count,
                   j.running_count = c.running_count, j.succeeded_count = c.succeeded_count,
                   j.failed_count = c.failed_count,
                   j.status = case
                     when c.running_count > 0 then 'RUNNING'
                     when c.pending_count > 0 then 'PENDING'
                     when c.failed_count = 0 then 'COMPLETED'
                     when c.succeeded_count > 0 then 'PARTIAL'
                     else 'FAILED' end,
                   j.updated_at = #{now}
            """)
    int refreshJob(@Param("packageId") long packageId, @Param("now") Instant now);

    @Update("""
            update vision_analysis_item set status = 'SUCCESS', heartbeat_at = null,
                   failure_code = null, updated_at = #{now}
             where id = #{itemId} and status = 'RUNNING'
               and analysis_generation = #{generation} and run_epoch = #{epoch}
            """)
    int completeWorkItem(@Param("itemId") long itemId, @Param("generation") long generation,
                         @Param("epoch") long epoch, @Param("now") Instant now);

    @Update("""
            update vision_analysis_item set status = 'FAILED', heartbeat_at = null,
                   failure_code = #{failureCode}, updated_at = #{now}
             where id = #{itemId} and status = 'RUNNING'
               and analysis_generation = #{generation} and run_epoch = #{epoch}
            """)
    int failWorkItem(@Param("itemId") long itemId, @Param("generation") long generation,
                     @Param("epoch") long epoch, @Param("failureCode") String failureCode,
                     @Param("now") Instant now);

    @Select("select id from vision_analysis_item where status = 'RUNNING' and heartbeat_at < #{before} "
            + "order by heartbeat_at limit #{limit}")
    List<Long> findStaleRunning(@Param("before") Instant before, @Param("limit") int limit);

    @Update("update vision_analysis_item set status = 'EXPIRED', updated_at = #{now} "
            + "where id = #{itemId} and status = 'RUNNING' and heartbeat_at < #{before}")
    int expireRunning(@Param("itemId") long itemId, @Param("before") Instant before,
                      @Param("now") Instant now);

    @Select("select id from vision_analysis_item where status = 'PENDING' and updated_at < #{before} "
            + "order by updated_at limit #{limit}")
    List<Long> findPendingBefore(@Param("before") Instant before, @Param("limit") int limit);

    @Insert(
            """
            insert into vision_analysis_item (
                package_id, sku_id, scope, target_image_id, input_fingerprint,
                status, analysis_generation, run_epoch, provider_attempt_count,
                structure_round_count, fact_start_version, failure_code, created_at, updated_at
            ) values (
                #{packageId}, #{skuId}, 'SKU', 0, #{fingerprint},
                'FAILED', 0, 0, 0, 0, 0, 'NOT_ANALYZED', #{now}, #{now}
            )
            on duplicate key update id = id
            """)
    int ensureSkuItem(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("fingerprint") String fingerprint,
            @Param("now") Instant now);

    @Select(
            """
            select i.id as item_id, i.package_id, i.sku_id, i.input_fingerprint, f.facts_json,
                   coalesce(f.version, 0) as fact_version, f.last_writer as writer,
                   f.updated_at as facts_updated_at, i.status as analysis_status,
                   i.analysis_generation, i.run_epoch, i.provider_attempt_count,
                   i.structure_round_count, i.last_request_id, i.failure_code
              from vision_analysis_item i
              left join asset_visual_analysis f
                on f.package_id = i.package_id and f.sku_id = i.sku_id
             where i.package_id = #{packageId} and i.sku_id = #{skuId}
               and i.scope = 'SKU' and i.target_image_id = 0
             limit 1
            """)
    VisionStateRow findSkuState(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId);

    @Select(
            """
            select i.id as item_id, i.package_id, i.sku_id, i.input_fingerprint, f.facts_json,
                   coalesce(f.version, 0) as fact_version, f.last_writer as writer,
                   f.updated_at as facts_updated_at, i.status as analysis_status,
                   i.analysis_generation, i.run_epoch, i.provider_attempt_count,
                   i.structure_round_count, i.last_request_id, i.failure_code
              from vision_analysis_item i
              left join asset_visual_analysis f
                on f.package_id = i.package_id and f.sku_id = i.sku_id
             where i.package_id = #{packageId} and i.sku_id = #{skuId}
               and i.scope = 'SKU' and i.target_image_id = 0
             limit 1 for update
            """)
    VisionStateRow lockSkuState(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId);

    @Select(
            """
            select distinct sku_id
              from vision_analysis_item
             where package_id = #{packageId} and scope = 'SKU' and target_image_id = 0
            """)
    Set<String> findKnownSkus(@Param("packageId") long packageId);

    @Insert(
            """
            insert into asset_visual_analysis (
                package_id, sku_id, input_fingerprint, facts_json, version,
                last_writer, created_at, updated_at
            ) values (
                #{packageId}, #{skuId}, #{fingerprint}, #{factsJson}, 1,
                'ADMINISTRATOR_EDITED', #{now}, #{now}
            )
            """)
    int insertAdministratorFacts(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("fingerprint") String fingerprint,
            @Param("factsJson") String factsJson,
            @Param("now") Instant now);

    @Update(
            """
            update asset_visual_analysis
               set facts_json = #{factsJson}, version = version + 1,
                   last_writer = 'ADMINISTRATOR_EDITED', updated_at = #{now}
             where package_id = #{packageId} and sku_id = #{skuId}
               and version = #{expectedVersion}
            """)
    int replaceAdministratorFacts(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("expectedVersion") long expectedVersion,
            @Param("factsJson") String factsJson,
            @Param("now") Instant now);

    @Update(
            """
            update asset_visual_analysis
               set input_fingerprint = #{fingerprint}, facts_json = null,
                   version = version + 1, last_writer = null,
                   operational_metadata = null, updated_at = #{now}
             where package_id = #{packageId} and sku_id = #{skuId}
               and input_fingerprint <> #{fingerprint}
            """)
    int invalidateFactsForInput(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("fingerprint") String fingerprint,
            @Param("now") Instant now);

    @Update(
            """
            update vision_analysis_item
               set input_fingerprint = #{fingerprint}, status = #{status},
                   analysis_generation = analysis_generation + 1, run_epoch = 0,
                   heartbeat_at = null, provider_attempt_count = 0,
                   structure_round_count = 0, last_request_id = null,
                   fact_start_version = #{factVersion}, failure_code = #{failureCode},
                   operational_metadata = null, updated_at = #{now}
             where id = #{itemId}
            """)
    int resetForInput(
            @Param("itemId") long itemId,
            @Param("fingerprint") String fingerprint,
            @Param("factVersion") long factVersion,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("now") Instant now);

    @Update(
            """
            update vision_analysis_item
               set input_fingerprint = #{fingerprint}, status = #{status},
                   analysis_generation = analysis_generation + 1, run_epoch = 0,
                   heartbeat_at = null, provider_attempt_count = 0,
                   structure_round_count = 0, last_request_id = #{requestId},
                   fact_start_version = #{factVersion}, failure_code = #{failureCode},
                   operational_metadata = null, updated_at = #{now}
             where package_id = #{packageId} and sku_id = #{skuId}
               and scope = 'SKU' and target_image_id = 0
               and analysis_generation = #{expectedGeneration}
            """)
    int resetForReanalysis(
            @Param("packageId") long packageId,
            @Param("skuId") String skuId,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("requestId") String requestId,
            @Param("fingerprint") String fingerprint,
            @Param("factVersion") long factVersion,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("now") Instant now);
}
