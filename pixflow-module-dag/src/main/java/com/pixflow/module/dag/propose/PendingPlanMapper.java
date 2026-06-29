package com.pixflow.module.dag.propose;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PendingPlanMapper extends BaseMapper<PendingPlan> {

    @Select("SELECT * FROM pending_plan WHERE tool_call_id = #{toolCallId} LIMIT 1")
    PendingPlan findByToolCallId(@Param("toolCallId") String toolCallId);

    @Select("SELECT * FROM pending_plan WHERE id = #{id}")
    PendingPlan findById(@Param("id") Long id);

    @Update("""
            UPDATE pending_plan
            SET status = #{status}, confirmed_at = #{confirmedAt}, task_id = #{taskId}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("confirmedAt") java.time.Instant confirmedAt,
                     @Param("taskId") String taskId);

    @Update("""
            UPDATE pending_plan
            SET status = 'EXPIRED'
            WHERE status = 'PENDING' AND expires_at < NOW(6)
            """)
    int expireOverdue();

    /**
     * 把所有指定大版本号之前的 PENDING 标 EXPIRED(用于 schema 大版本升级时清理)。
     */
    @Update("""
            UPDATE pending_plan
            SET status = 'EXPIRED'
            WHERE status = 'PENDING' AND schema_version < #{cutoffMajorVersion}
            """)
    int expireByOldSchema(@Param("cutoffMajorVersion") String cutoffMajorVersion);
}