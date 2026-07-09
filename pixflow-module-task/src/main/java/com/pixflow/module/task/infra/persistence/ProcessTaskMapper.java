package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProcessTaskMapper extends BaseMapper<ProcessTask> {
    @Select("select * from process_task where idempotency_key = #{key} limit 1")
    ProcessTask findByIdempotencyKey(@Param("key") String key);

    @Select("""
            select * from process_task
            where id = #{taskId} and conversation_id = #{conversationId}
            limit 1
            """)
    ProcessTask findByIdAndConversation(@Param("taskId") long taskId, @Param("conversationId") String conversationId);

    @Select("""
            select * from process_task
            where status = #{status}
            order by started_at asc
            limit #{limit}
            """)
    List<ProcessTask> findByStatus(@Param("status") TaskStatus status, @Param("limit") int limit);

    @Update("""
            update process_task
            set status = #{to}, updated_at = #{now}
            where id = #{taskId} and status = #{from}
            """)
    int transit(@Param("taskId") long taskId, @Param("from") TaskStatus from,
                @Param("to") TaskStatus to, @Param("now") Instant now);

    @Update("""
            update process_task
            set status = #{to}, worker_id = #{workerId}, started_at = coalesce(started_at, #{now}),
                attempt_count = attempt_count + 1, updated_at = #{now}
            where id = #{taskId} and status = #{from}
            """)
    int markRunning(@Param("taskId") long taskId, @Param("from") TaskStatus from,
                    @Param("to") TaskStatus to, @Param("workerId") String workerId,
                    @Param("now") Instant now);

    @Update("""
            update process_task
            set status = #{status}, done_count = #{done}, total_count = #{total},
                finished_at = #{now}, updated_at = #{now}, last_error = #{lastError}
            where id = #{taskId}
            """)
    int markTerminal(@Param("taskId") long taskId, @Param("status") TaskStatus status,
                     @Param("total") int total, @Param("done") int done,
                     @Param("lastError") String lastError, @Param("now") Instant now);

    @Update("""
            update process_task
            set heartbeat_at = #{now}, updated_at = #{now}
            where id = #{taskId}
            """)
    int heartbeat(@Param("taskId") long taskId, @Param("now") Instant now);
}
