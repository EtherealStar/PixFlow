package com.pixflow.agent.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Skill 持久化实体（{@code @TableName("skill")}）。
 *
 * <p>对应 {@code agent.md §5.4} 11 字段：id / name / description / when_to_use /
 * body / source / version / body_hash / created_at / updated_at。
 *
 * <p>本期 BUILTIN 由启动期 SkillLoader 同步入表；
 * PROJECT / TEAM 保留 enum 扩展但暂不启用。
 */
@TableName("skill")
public class Skill {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("when_to_use")
    private String whenToUse;

    @TableField("body")
    private String body;

    @TableField("source")
    private String source;

    @TableField("version")
    private Integer version;

    @TableField("body_hash")
    private String bodyHash;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    public Skill() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getBodyHash() { return bodyHash; }
    public void setBodyHash(String bodyHash) { this.bodyHash = bodyHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}