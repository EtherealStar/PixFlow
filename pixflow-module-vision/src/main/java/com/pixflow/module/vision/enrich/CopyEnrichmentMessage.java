package com.pixflow.module.vision.enrich;

public record CopyEnrichmentMessage(long packageId) {
    public CopyEnrichmentMessage {
        if (packageId <= 0) {
            throw new IllegalArgumentException("packageId must be positive");
        }
    }
}
