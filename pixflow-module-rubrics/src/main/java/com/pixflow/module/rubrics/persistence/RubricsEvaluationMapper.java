package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RubricsEvaluationMapper extends BaseMapper<RubricsEvaluationEntity> {
    @Select("select * from rubrics_evaluation where subject_type=#{type} and subject_id=#{subjectId} order by created_at desc")
    List<RubricsEvaluationEntity> history(@Param("type") SubjectType type, @Param("subjectId") String subjectId);
}
