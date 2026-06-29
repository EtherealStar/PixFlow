package com.pixflow.module.dag.propose;

import java.time.Instant;
import java.util.Objects;

/**
 * pending_plan 实体(MyBatis-Plus)。注意:
 * <ul>
 *   <li>{@code dagJson} 字段是规范化后的 DAG JSON 字符串(用于重校验)</li>
 *   <li>{@code payloadHash} = sha256(canonicalJson(dagJson)),与 UnitKey.branchId 同源理念</li>
 *   <li>{@code schemaVersion} 大版本号,确认时双边校验</li>
 *   <li>{@code toolCallId} 重复时幂等(同 toolCallId 不产生新 plan)</li>
 * </ul>
 */
public class PendingPlan {
    private Long id;
    private String toolCallId;
    private String conversationId;
    private String type;            // IMAGE_PLAN / IMAGEGEN
    private String dagJson;
    private String payloadHash;
    private String schemaVersion;
    private String note;
    private PendingPlanStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant confirmedAt;
    private String taskId;

    public PendingPlan() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = Objects.requireNonNull(type, "type"); }
    public String getDagJson() { return dagJson; }
    public void setDagJson(String dagJson) { this.dagJson = dagJson; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public PendingPlanStatus getStatus() { return status; }
    public void setStatus(PendingPlanStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
}