package com.pixflow.harness.session.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CompactionMapper {

    @Select("""
            SELECT id, conversation_id AS conversationId, boundary_message_id AS boundaryMessageId,
                   summary_message_id AS summaryMessageId, covered_up_to_seq AS coveredUpToSeq,
                   trigger, metadata, created_at AS createdAt
            FROM message_compaction
            WHERE conversation_id = #{conversationId}
            ORDER BY id DESC
            LIMIT 1
            """)
    CompactionEntity findLatest(@Param("conversationId") String conversationId);

    @Insert("""
            INSERT INTO message_compaction
              (conversation_id, boundary_message_id, summary_message_id, covered_up_to_seq,
               trigger, metadata, created_at)
            VALUES
              (#{conversationId}, #{boundaryMessageId}, #{summaryMessageId}, #{coveredUpToSeq},
               #{trigger}, CAST(#{metadata} AS JSON), #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CompactionEntity entity);
}
