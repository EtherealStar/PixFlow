package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RubricsJudgeRolloutMapper extends BaseMapper<RubricsJudgeRolloutEntity> {

    @Select("select * from rubrics_judge_rollout where criterion_result_id=#{criterionResultId} order by rollout_index")
    List<RubricsJudgeRolloutEntity> findByCriterionResultId(@Param("criterionResultId") long criterionResultId);
}
