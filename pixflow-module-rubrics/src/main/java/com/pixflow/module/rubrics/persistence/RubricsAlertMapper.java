package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RubricsAlertMapper extends BaseMapper<RubricsAlertEntity> {
    @Select("""
            select * from rubrics_alert
            where acknowledged = #{acknowledged}
            order by created_at desc
            limit #{limit}
            """)
    List<RubricsAlertEntity> findByAcknowledged(@Param("acknowledged") boolean acknowledged, @Param("limit") int limit);

    @Update("""
            update rubrics_alert
            set acknowledged = true, acknowledged_at = #{now}
            where id = #{alertId}
            """)
    int acknowledge(@Param("alertId") long alertId, @Param("now") Instant now);
}
