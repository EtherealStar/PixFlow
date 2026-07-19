package com.pixflow.module.vision.persistence;

import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.api.VisualFactsWriter;
import java.time.Instant;

public class VisionStateRow {
    private long itemId;

    private long packageId;

    private String skuId;

    private String inputFingerprint;

    private String factsJson;

    private long factVersion;

    private VisualFactsWriter writer;

    private Instant factsUpdatedAt;

    private AnalysisStatus analysisStatus;

    private long analysisGeneration;

    private long runEpoch;

    private int providerAttemptCount;

    private int structureRoundCount;

    private String lastRequestId;

    private String failureCode;

    public long getItemId() {
        return itemId;
    }

    public void setItemId(long itemId) {
        this.itemId = itemId;
    }

    public long getPackageId() {
        return packageId;
    }

    public void setPackageId(long packageId) {
        this.packageId = packageId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public void setInputFingerprint(String inputFingerprint) {
        this.inputFingerprint = inputFingerprint;
    }

    public String getFactsJson() {
        return factsJson;
    }

    public void setFactsJson(String factsJson) {
        this.factsJson = factsJson;
    }

    public long getFactVersion() {
        return factVersion;
    }

    public void setFactVersion(long factVersion) {
        this.factVersion = factVersion;
    }

    public VisualFactsWriter getWriter() {
        return writer;
    }

    public void setWriter(VisualFactsWriter writer) {
        this.writer = writer;
    }

    public Instant getFactsUpdatedAt() {
        return factsUpdatedAt;
    }

    public void setFactsUpdatedAt(Instant factsUpdatedAt) {
        this.factsUpdatedAt = factsUpdatedAt;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public long getAnalysisGeneration() {
        return analysisGeneration;
    }

    public void setAnalysisGeneration(long analysisGeneration) {
        this.analysisGeneration = analysisGeneration;
    }

    public long getRunEpoch() {
        return runEpoch;
    }

    public void setRunEpoch(long runEpoch) {
        this.runEpoch = runEpoch;
    }

    public int getProviderAttemptCount() {
        return providerAttemptCount;
    }

    public void setProviderAttemptCount(int providerAttemptCount) {
        this.providerAttemptCount = providerAttemptCount;
    }

    public int getStructureRoundCount() {
        return structureRoundCount;
    }

    public void setStructureRoundCount(int structureRoundCount) {
        this.structureRoundCount = structureRoundCount;
    }

    public String getLastRequestId() {
        return lastRequestId;
    }

    public void setLastRequestId(String lastRequestId) {
        this.lastRequestId = lastRequestId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }
}
