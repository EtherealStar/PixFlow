package com.pixflow.module.rubrics.persistence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import java.util.List; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Param; import org.apache.ibatis.annotations.Select;
@Mapper public interface RubricsCriterionResultMapper extends BaseMapper<RubricsCriterionResultEntity> {
 @Select("select * from rubrics_criterion_result where evaluation_id=#{evaluationId} order by id") List<RubricsCriterionResultEntity> findByEvaluationId(@Param("evaluationId") long evaluationId);
}
