package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
            where task_id = #{taskId}
              and branch_id = #{branchId}
              and (image_id = #{memberId} or group_key = #{memberId})
            limit 1
            """)
    ProcessResult findByUnit(@Param("taskId") long taskId, @Param("memberId") String memberId,
                             @Param("branchId") String branchId);

    @Select("select count(*) from process_result where task_id = #{taskId} and status = #{status}")
    int countByStatus(@Param("taskId") long taskId, @Param("status") ResultStatus status);
}
