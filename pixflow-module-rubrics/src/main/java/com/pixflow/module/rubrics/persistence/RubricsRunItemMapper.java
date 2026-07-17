package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.rubrics.run.RunItemStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RubricsRunItemMapper extends BaseMapper<RubricsRunItemEntity> {
    @Select("""
            select * from rubrics_run_item
            where run_id = #{runId}
            order by id asc
            """)
    List<RubricsRunItemEntity> findByRunId(@Param("runId") long runId);

    @Select("""
            select * from rubrics_run_item
            where run_id = #{runId} and status in ('PENDING', 'RUNNING', 'ISOLATED', 'FAILED')
            order by id asc
            """)
    List<RubricsRunItemEntity> findIncompleteByRunId(@Param("runId") long runId);

    @Update("""
            update rubrics_run_item
            set status = #{status}, attempt_count = attempt_count + 1, started_at = coalesce(started_at, #{now}),
                updated_at = #{now}
            where id = #{itemId} and attempt_count = #{expectedAttemptCount}
            """)
    int markRunning(@Param("itemId") long itemId, @Param("expectedAttemptCount") int expectedAttemptCount,
                    @Param("status") RunItemStatus status, @Param("now") Instant now);

    @Update("""
            update rubrics_run_item
            set status = #{status}, error_msg = #{errorMsg}, finished_at = #{now}, updated_at = #{now}
            where id = #{itemId} and status = 'RUNNING' and attempt_count = #{attemptCount}
            """)
    int markFinished(@Param("itemId") long itemId,
                     @Param("attemptCount") int attemptCount,
                     @Param("status") RunItemStatus status,
                     @Param("errorMsg") String errorMsg,
                     @Param("now") Instant now);
}
