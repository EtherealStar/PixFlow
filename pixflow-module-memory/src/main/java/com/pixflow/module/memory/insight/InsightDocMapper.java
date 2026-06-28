package com.pixflow.module.memory.insight;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.memory.recall.InsightFilter;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InsightDocMapper extends BaseMapper<AnalysisInsight> {
    @Select("""
            <script>
            SELECT *
            FROM analysis_insight
            WHERE status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > NOW())
              AND MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
              <if test="filter.categories != null and filter.categories.size > 0">
              AND category IN
              <foreach item="category" collection="filter.categories" open="(" separator="," close=")">
                  #{category}
              </foreach>
              </if>
              <if test="filter.skuIds != null and filter.skuIds.size > 0">
              AND related_sku IN
              <foreach item="skuId" collection="filter.skuIds" open="(" separator="," close=")">
                  #{skuId}
              </foreach>
              </if>
              <if test="filter.minConfidence > 0">
              AND confidence &gt;= #{filter.minConfidence}
              </if>
            ORDER BY MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE) DESC, updated_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<AnalysisInsight> fulltextSearch(
            @Param("query") String query,
            @Param("filter") InsightFilter filter,
            @Param("limit") int limit);
}
