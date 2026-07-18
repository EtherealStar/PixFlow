package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProcessResultMemberMapper extends BaseMapper<ProcessResultMember> {
  @Select("select * from process_result_member where result_id = #{resultId} order by id asc")
  List<ProcessResultMember> findByResultId(@Param("resultId") long resultId);
}
