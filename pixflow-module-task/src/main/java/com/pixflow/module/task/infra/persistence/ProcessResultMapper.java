package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProcessResultMapper extends BaseMapper<ProcessResult> {
    @Select("select * from process_result where task_id = #{taskId} order by id asc")
    List<ProcessResult> findByTaskId(@Param("taskId") long taskId);

    @Select("""
            select * from process_result
            where task_id = #{taskId} and status = #{status}
            order by id asc
            """)
    List<ProcessResult> findByTaskIdAndStatus(@Param("taskId") long taskId,
                                               @Param("status") ResultStatus status);

    @Select("""
            select * from process_result
            where task_id = #{taskId} and status = #{status} and deleted_at is null
            order by id asc
            """)
    List<ProcessResult> findVisibleByTaskIdAndStatus(@Param("taskId") long taskId,
                                                     @Param("status") ResultStatus status);

    @Select("""
            select * from process_result
            where task_id = #{taskId}
              and branch_id = #{branchId}
              and (image_id = #{memberId} or group_key = #{memberId})
            limit 1
            """)
    ProcessResult findByUnit(@Param("taskId") long taskId, @Param("memberId") String memberId,
                             @Param("branchId") String branchId);

    @Select("select count(*) from process_result where task_id = #{taskId} and status = #{status}")
    int countByStatus(@Param("taskId") long taskId, @Param("status") ResultStatus status);

    @Select("""
            select * from process_result
            where task_id = #{taskId} and deleted_at is null
            order by id asc
            limit #{size} offset #{offset}
            """)
    List<ProcessResult> pageVisibleByTaskId(@Param("taskId") long taskId,
                                            @Param("offset") long offset,
                                            @Param("size") int size);

    @Select("select count(*) from process_result where task_id = #{taskId} and deleted_at is null")
    long countVisibleByTaskId(@Param("taskId") long taskId);

    @Select("""
            select r.* from process_result r
            join process_task t on t.id = r.task_id
            where t.conversation_id = #{conversationId}
              and r.status = 'SUCCESS'
              and r.output_minio_key is not null
              and r.deleted_at is null
            order by r.created_at desc, r.id desc
            limit #{size} offset #{offset}
            """)
    List<ProcessResult> pageConversationImages(@Param("conversationId") String conversationId,
                                               @Param("offset") long offset,
                                               @Param("size") int size);

    @Select("""
            select count(*) from process_result r
            join process_task t on t.id = r.task_id
            where t.conversation_id = #{conversationId}
              and r.status = 'SUCCESS'
              and r.output_minio_key is not null
              and r.deleted_at is null
            """)
    long countConversationImages(@Param("conversationId") String conversationId);

    @Update("""
            update process_result
            set deleted_at = #{deletedAt}
            where id = #{resultId} and task_id = #{taskId} and deleted_at is null
            """)
    int softDelete(@Param("taskId") long taskId, @Param("resultId") long resultId,
                   @Param("deletedAt") Instant deletedAt);

    @Update("""
            update process_result
            set display_name = #{displayName}
            where id = #{resultId} and task_id = #{taskId} and deleted_at is null
            """)
    int updateDisplayName(@Param("taskId") long taskId, @Param("resultId") long resultId,
                          @Param("displayName") String displayName);
}
