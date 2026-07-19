package com.pixflow.module.vision.persistence;

import java.time.Instant;
import java.util.Set;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VisionStateMapper {
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
