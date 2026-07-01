package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RubricsBaselineMapper extends BaseMapper<RubricsBaselineEntity> {
    @Select("""
            select * from rubrics_baseline
            where template_id = #{templateId} and active = true
            order by created_at desc
            limit 1
            """)
    RubricsBaselineEntity findActive(@Param("templateId") String templateId);

    @Update("""
            update rubrics_baseline
            set active = false
            where template_id = #{templateId} and active = true
            """)
    int deactivateActive(@Param("templateId") String templateId);

}
