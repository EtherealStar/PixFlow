package com.pixflow.harness.session.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 真实删除 Conversation transcript 的专用生命周期 mapper。 */
@Mapper
public interface TranscriptDeletionMapper {
    @Delete("DELETE FROM message_compaction WHERE conversation_id = #{conversationId}")
    int deleteCompactions(@Param("conversationId") String conversationId);

    @Delete("DELETE FROM message WHERE conversation_id = #{conversationId}")
    int deleteMessages(@Param("conversationId") String conversationId);
}
