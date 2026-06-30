package com.pixflow.agent.sessionmemory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Session Memory 持久化实体（{@code @TableName("session_memory")}）。
 *
 * <p>对应 {@code agent.md §7.2.1} 7 字段：conversation_id / content /
 * last_summarized_seq / covered_turn_count / source / content_hash / 时间戳。
 *
 * <p>单行 per session — 区别于 transcript 的 append-only 历史，session memory 是
 * 当前活跃状态的压缩视图。
 */
@TableName("session_memory")
public class SessionMemory {

    @TableId(type = IdType.INPUT)
    @TableField("conversation_id")
    private String conversationId;

    @TableField("content")
    private String content;

    @TableField("last_summarized_seq")
    private Long lastSummarizedSeq;

    @TableField("covered_turn_count")
    private Integer coveredTurnCount;

    @TableField("source")
    private String source;

    @TableField("content_hash")
    private String contentHash;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    public SessionMemory() {}

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getLastSummarizedSeq() { return lastSummarizedSeq; }
    public void setLastSummarizedSeq(Long lastSummarizedSeq) { this.lastSummarizedSeq = lastSummarizedSeq; }

    public Integer getCoveredTurnCount() { return coveredTurnCount; }
    public void setCoveredTurnCount(Integer coveredTurnCount) { this.coveredTurnCount = coveredTurnCount; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}