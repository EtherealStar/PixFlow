package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RubricsScoreMapper extends BaseMapper<RubricsScoreEntity> {
    @Select("select * from rubrics_score where result_id = #{resultId} limit 1")
    RubricsScoreEntity findByResultId(@Param("resultId") long resultId);

    @Select("""
            select * from rubrics_score
            where run_id = #{runId}
            order by result_id asc
            """)
    List<RubricsScoreEntity> findByRunId(@Param("runId") long runId);

    @Select("""
            <script>
            select * from rubrics_score
            where result_id in
            <foreach collection="resultIds" item="resultId" open="(" separator="," close=")">#{resultId}</foreach>
            order by result_id asc
            </script>
            """)
    List<RubricsScoreEntity> findByResultIds(@Param("resultIds") Collection<Long> resultIds);

    @Select("""
            select s.*
            from rubrics_score s
            join process_result r on r.id = s.result_id
            where r.sku_id = #{skuId}
            order by s.created_at desc
            limit #{limit}
            """)
    List<RubricsScoreEntity> findBySkuId(@Param("skuId") String skuId, @Param("limit") int limit);

    @Insert("""
            insert into rubrics_score
              (result_id, task_id, run_id, template_id, template_version,
               overall_score, image_score, copy_score, decision_score,
               dimension_scores_json, explanation_json, alert_flag, created_at)
            values
              (#{score.resultId}, #{score.taskId}, #{score.runId}, #{score.templateId}, #{score.templateVersion},
               #{score.overallScore}, #{score.imageScore}, #{score.copyScore}, #{score.decisionScore},
               #{score.dimensionScoresJson}, #{score.explanationJson}, #{score.alertFlag}, #{score.createdAt})
            on duplicate key update
              run_id = values(run_id),
              template_id = values(template_id),
              template_version = values(template_version),
              overall_score = values(overall_score),
              image_score = values(image_score),
              copy_score = values(copy_score),
              decision_score = values(decision_score),
              dimension_scores_json = values(dimension_scores_json),
              explanation_json = values(explanation_json),
              alert_flag = values(alert_flag),
              created_at = values(created_at)
            """)
    int upsert(@Param("score") RubricsScoreEntity score);
}
