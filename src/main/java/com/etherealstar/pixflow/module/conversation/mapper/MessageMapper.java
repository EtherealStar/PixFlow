package com.etherealstar.pixflow.module.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.etherealstar.pixflow.module.conversation.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code message} 表 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
