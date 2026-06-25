package com.etherealstar.pixflow.module.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 消息实体，对应表 {@code message}。
 * <p>{@code taskId} 记录该消息触发的处理任务关联（需求 5.6），可空。
 */
@TableName("message")
public class Message {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属对话 id（软关联） */
    private Long conversationId;

    /** user / assistant */
    private String role;

    /** 消息内容，最大 4000 字符 */
    private String content;

    /** 关联素材包 id，可空 */
    private Long attachedPackageId;

    /** 该消息触发的处理任务 id，可空（需求 5.6） */
    private Long taskId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getAttachedPackageId() {
        return attachedPackageId;
    }

    public void setAttachedPackageId(Long attachedPackageId) {
        this.attachedPackageId = attachedPackageId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
