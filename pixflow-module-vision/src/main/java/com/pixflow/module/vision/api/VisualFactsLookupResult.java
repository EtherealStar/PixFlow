package com.pixflow.module.vision.api;

import java.util.List;
import java.util.Objects;

/**
 * Agent 可见的最小事实投影；不包含 writer、provider 或执行元数据。
 */
public record VisualFactsLookupResult(
        VisualFactsLookupStatus status,
        String requestedReferenceKey,
        List<VisualFactsScope> scopes,
        boolean truncated,
        String safeReason) {

    public VisualFactsLookupResult {
        status = Objects.requireNonNull(status, "status");
        requestedReferenceKey = Objects.requireNonNull(requestedReferenceKey, "requestedReferenceKey");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }
}
