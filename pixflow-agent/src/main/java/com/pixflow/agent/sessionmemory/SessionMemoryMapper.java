package com.pixflow.agent.sessionmemory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
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

    @Insert("""
            INSERT INTO session_memory (
                conversation_id,
                content,
                last_summarized_seq,
                covered_turn_count,
                source,
                content_hash,
                created_at,
                updated_at
            ) VALUES (
                #{conversationId},
                #{content},
                #{lastSummarizedSeq},
                #{coveredTurnCount},
                #{source},
                #{contentHash},
                #{createdAt},
                #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                content = IF(VALUES(last_summarized_seq) >= last_summarized_seq, VALUES(content), content),
                content_hash = IF(VALUES(last_summarized_seq) >= last_summarized_seq, VALUES(content_hash), content_hash),
                source = IF(VALUES(last_summarized_seq) >= last_summarized_seq, VALUES(source), source),
                covered_turn_count = IF(VALUES(last_summarized_seq) >= last_summarized_seq, VALUES(covered_turn_count), covered_turn_count),
                updated_at = IF(VALUES(last_summarized_seq) >= last_summarized_seq, VALUES(updated_at), updated_at),
                last_summarized_seq = GREATEST(last_summarized_seq, VALUES(last_summarized_seq))
            """)
    int upsertIfAdvances(SessionMemory memory);
}
