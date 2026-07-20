package com.pixflow.module.rubrics.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.rubrics.run.RunStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RubricsRunMapper extends BaseMapper<RubricsRunEntity> {
    @Select("select * from rubrics_run where admission_key = #{admissionKey} limit 1")
    RubricsRunEntity findByAdmissionKey(@Param("admissionKey") String admissionKey);

    @Select("""
            select * from rubrics_run
            where status = #{status}
            order by created_at asc
            """)
    List<RubricsRunEntity> findByStatus(@Param("status") RunStatus status);

    @Select("""
            select * from rubrics_run
            where status in ('PENDING', 'RUNNING')
            order by updated_at asc
            limit #{limit}
            """)
    List<RubricsRunEntity> findRecoverable(@Param("limit") int limit);

    @Update("""
            update rubrics_run
            set status = #{status}, succeeded_count = #{succeeded}, isolated_count = #{isolated},
                failed_count = #{failed}, finished_at = #{finishedAt}, updated_at = #{finishedAt},
                error_msg = #{errorMsg}
            where id = #{runId}
            """)
    int markFinished(@Param("runId") long runId,
                     @Param("status") RunStatus status,
                     @Param("succeeded") int succeeded,
                     @Param("isolated") int isolated,
                     @Param("failed") int failed,
                     @Param("errorMsg") String errorMsg,
                     @Param("finishedAt") Instant finishedAt);
}
