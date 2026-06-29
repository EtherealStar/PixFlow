package com.pixflow.harness.session.persistence;

import java.time.Instant;

public class CompactionEntity {
    private Long id;
    private String conversationId;
    private String boundaryMessageId;
    private String summaryMessageId;
    private Long coveredUpToSeq;
    private String trigger;
    private String metadata;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getBoundaryMessageId() {
        return boundaryMessageId;
    }

    public void setBoundaryMessageId(String boundaryMessageId) {
        this.boundaryMessageId = boundaryMessageId;
    }

    public String getSummaryMessageId() {
        return summaryMessageId;
    }

    public void setSummaryMessageId(String summaryMessageId) {
        this.summaryMessageId = summaryMessageId;
    }

    public Long getCoveredUpToSeq() {
        return coveredUpToSeq;
    }

    public void setCoveredUpToSeq(Long coveredUpToSeq) {
        this.coveredUpToSeq = coveredUpToSeq;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
