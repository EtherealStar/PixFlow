package com.etherealstar.pixflow.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 处理任务实体，对应表 {@code process_task}。
 * <p>{@code dagJson} 以字符串形式承载 MySQL JSON 列内容。
 */
@TableName("process_task")
public class ProcessTask {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源对话 id（软关联） */
    private Long conversationId;

    /** 处理的素材包 id（软关联） */
    private Long packageId;

    /** LLM 解析出的 DAG 结构（JSON 字符串） */
    private String dagJson;

    /** 0 待执行 1 执行中 2 完成 3 失败 */
    private Integer status;

    /** 总图片数 */
    private Integer totalCount;

    /** 已完成数 */
    private Integer doneCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 执行结束时刻 */
    private LocalDateTime finishedAt;

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

    public Long getPackageId() {
        return packageId;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public String getDagJson() {
        return dagJson;
    }

    public void setDagJson(String dagJson) {
        this.dagJson = dagJson;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getDoneCount() {
        return doneCount;
    }

    public void setDoneCount(Integer doneCount) {
        this.doneCount = doneCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
