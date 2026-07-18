package com.pixflow.harness.session.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageWriteMapper {

    @Select("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = #{conversationId}")
    long maxSeq(@Param("conversationId") String conversationId);

    @Insert("""
            <script>
            INSERT IGNORE INTO message
              (id, conversation_id, seq, role, content, tool_call_id, compaction_marker, metadata,
               task_id, created_at)
            VALUES
            <foreach collection="messages" item="message" separator=",">
              (#{message.id}, #{message.conversationId}, #{message.seq}, #{message.role}, #{message.content},
               #{message.toolCallId}, #{message.compactionMarker}, CAST(#{message.metadata} AS JSON),
               #{message.taskId}, #{message.createdAt})
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("messages") List<MessageEntity> messages);
}
