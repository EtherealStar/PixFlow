package com.pixflow.module.commerce.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.commerce.query.PeriodType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CommerceDataMapper extends BaseMapper<CommerceData> {
    @Insert("""
            INSERT INTO commerce_data
              (sku_id, category, impressions, ctr, add_cart_rate, purchase_rate,
               period_type, period_start, period_end, source, fetched_at, created_at, updated_at)
            VALUES
              (#{row.skuId}, #{row.category}, #{row.impressions}, #{row.ctr}, #{row.addCartRate}, #{row.purchaseRate},
               #{row.periodType}, #{row.periodStart}, #{row.periodEnd}, #{row.source}, #{row.fetchedAt}, #{row.createdAt}, #{row.updatedAt})
            ON DUPLICATE KEY UPDATE
              category = VALUES(category),
              impressions = VALUES(impressions),
              ctr = VALUES(ctr),
              add_cart_rate = VALUES(add_cart_rate),
              purchase_rate = VALUES(purchase_rate),
              period_end = VALUES(period_end),
              fetched_at = VALUES(fetched_at),
              updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("row") CommerceData row);

    @Select("""
            <script>
            SELECT sku_id AS skuId,
                   SUBSTRING_INDEX(GROUP_CONCAT(category ORDER BY fetched_at DESC), ',', 1) AS category,
                   SUM(impressions) AS impressions,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(ctr * impressions) / SUM(impressions) END AS ctr,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(add_cart_rate * impressions) / SUM(impressions) END AS addCartRate,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(purchase_rate * impressions) / SUM(impressions) END AS purchaseRate,
                   MAX(fetched_at) AS fetchedAt,
                   SUBSTRING_INDEX(GROUP_CONCAT(source ORDER BY fetched_at DESC), ',', 1) AS source
            FROM commerce_data
            WHERE sku_id IN
            <foreach collection="skuIds" item="sku" open="(" separator="," close=")">#{sku}</foreach>
              AND period_type = #{periodType}
              AND period_end &gt;= #{from}
              AND period_start &lt;= #{to}
              <if test="sources != null and sources.size() > 0">
              AND source IN
              <foreach collection="sources" item="source" open="(" separator="," close=")">#{source}</foreach>
              </if>
            GROUP BY sku_id
            </script>
            """)
    List<CommerceAggregateRow> aggregateBySku(
            @Param("skuIds") Collection<String> skuIds,
            @Param("periodType") PeriodType periodType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("sources") Collection<String> sources);

    @Select("""
            <script>
            SELECT category AS category,
                   SUM(impressions) AS impressions,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(ctr * impressions) / SUM(impressions) END AS ctr,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(add_cart_rate * impressions) / SUM(impressions) END AS addCartRate,
                   CASE WHEN SUM(impressions) = 0 THEN 0 ELSE SUM(purchase_rate * impressions) / SUM(impressions) END AS purchaseRate,
                   COUNT(DISTINCT sku_id) AS sampleCount
            FROM commerce_data
            WHERE category = #{category}
              AND period_type = #{periodType}
              AND period_end &gt;= #{from}
              AND period_start &lt;= #{to}
              <if test="sources != null and sources.size() > 0">
              AND source IN
              <foreach collection="sources" item="source" open="(" separator="," close=")">#{source}</foreach>
              </if>
            GROUP BY category
            </script>
            """)
    CommerceBenchmarkRow categoryBenchmark(
            @Param("category") String category,
            @Param("periodType") PeriodType periodType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("sources") Collection<String> sources);

    @Select("""
            <script>
            SELECT *
            FROM commerce_data
            WHERE sku_id = #{skuId}
              AND period_type = #{periodType}
              AND period_end &gt;= #{from}
              AND period_start &lt;= #{to}
              <if test="sources != null and sources.size() > 0">
              AND source IN
              <foreach collection="sources" item="source" open="(" separator="," close=")">#{source}</foreach>
              </if>
            ORDER BY period_start ASC
            </script>
            """)
    List<CommerceData> trend(
            @Param("skuId") String skuId,
            @Param("periodType") PeriodType periodType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("sources") Collection<String> sources);
}
