package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcessResultMemberMapper extends BaseMapper<ProcessResultMember> {
}
