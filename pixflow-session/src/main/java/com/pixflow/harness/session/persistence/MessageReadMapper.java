package com.pixflow.harness.session.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageReadMapper {

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE id = #{id}
            """)
    MessageEntity findById(@Param("id") String id);

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE conversation_id = #{conversationId} AND compaction_marker IS NULL
            ORDER BY seq
            """)
    List<MessageEntity> findNormalMessages(@Param("conversationId") String conversationId);

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE conversation_id = #{conversationId}
              AND compaction_marker IS NULL
              AND seq > #{coveredUpToSeq}
            ORDER BY seq
            """)
    List<MessageEntity> findNormalMessagesAfter(
            @Param("conversationId") String conversationId,
            @Param("coveredUpToSeq") long coveredUpToSeq);

    @Select("""
            <script>
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    List<MessageEntity> findByIds(@Param("ids") List<String> ids);

    @Select("""
            SELECT COALESCE(MAX(seq), 0)
            FROM message
            WHERE conversation_id = #{conversationId} AND compaction_marker IS NULL
            """)
    long maxNormalSeq(@Param("conversationId") String conversationId);

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE conversation_id = #{conversationId}
            ORDER BY seq
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MessageReadView> findMessagesByConversation(
            @Param("conversationId") String conversationId,
            @Param("offset") long offset,
            @Param("limit") long limit);

    @Select("SELECT COUNT(*) FROM message WHERE conversation_id = #{conversationId}")
    long countMessagesByConversation(@Param("conversationId") String conversationId);

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, content,
                   tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                   attached_package_id AS attachedPackageId, task_id AS taskId, created_at AS createdAt
            FROM message
            WHERE conversation_id = #{conversationId} AND role = 'ATTACHMENT'
            ORDER BY seq
            """)
    List<MessageReadView> findAttachments(@Param("conversationId") String conversationId);
}
