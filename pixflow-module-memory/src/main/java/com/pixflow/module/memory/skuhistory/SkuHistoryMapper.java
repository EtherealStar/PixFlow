package com.pixflow.module.memory.skuhistory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkuHistoryMapper extends BaseMapper<SkuHistory> {
    @Select("""
            <script>
            SELECT id, sku_id, task_id, params_json, metrics_before, metrics_after, created_at
            FROM (
                SELECT id, sku_id, task_id, params_json, metrics_before, metrics_after, created_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY sku_id
                           ORDER BY created_at DESC, id ASC
                       ) AS row_no
                FROM sku_history
                WHERE sku_id IN
                <foreach collection="skuIds" item="skuId" open="(" separator="," close=")">
                    #{skuId}
                </foreach>
            ) ranked
            WHERE row_no &lt;= #{maxItemsPerSku}
            ORDER BY created_at DESC, id ASC
            </script>
            """)
    List<SkuHistory> selectRecentPerSku(
            @Param("skuIds") List<String> skuIds,
            @Param("maxItemsPerSku") int maxItemsPerSku);
}
