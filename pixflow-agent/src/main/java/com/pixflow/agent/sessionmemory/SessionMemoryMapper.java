package com.pixflow.agent.sessionmemory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * Session Memory MyBatis-Plus Mapper。
 */
@Mapper
public interface SessionMemoryMapper extends BaseMapper<SessionMemory> {

    @Select("SELECT * FROM session_memory WHERE conversation_id = #{conversationId} LIMIT 1")
    Optional<SessionMemory> findByConversationId(String conversationId);
}