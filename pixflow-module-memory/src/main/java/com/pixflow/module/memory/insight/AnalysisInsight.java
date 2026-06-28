package com.pixflow.module.memory.insight;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("analysis_insight")
public class AnalysisInsight {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String text;
    private String category;
    private String source;
    private Double confidence;
    private String relatedSku;
    private String contentHash;
    private Double importance;
    private AnalysisInsightStatus status;
    private Integer accessCount;
    private Instant lastRecalledAt;
    private Instant lastReinforcedAt;
    private Double decayScore;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getRelatedSku() {
        return relatedSku;
    }

    public void setRelatedSku(String relatedSku) {
        this.relatedSku = relatedSku;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Double getImportance() {
        return importance;
    }

    public void setImportance(Double importance) {
        this.importance = importance;
    }

    public AnalysisInsightStatus getStatus() {
        return status;
    }

    public void setStatus(AnalysisInsightStatus status) {
        this.status = status;
    }

    public Integer getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }

    public Instant getLastRecalledAt() {
        return lastRecalledAt;
    }

    public void setLastRecalledAt(Instant lastRecalledAt) {
        this.lastRecalledAt = lastRecalledAt;
    }

    public Instant getLastReinforcedAt() {
        return lastReinforcedAt;
    }

    public void setLastReinforcedAt(Instant lastReinforcedAt) {
        this.lastReinforcedAt = lastReinforcedAt;
    }

    public Double getDecayScore() {
        return decayScore;
    }

    public void setDecayScore(Double decayScore) {
        this.decayScore = decayScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
