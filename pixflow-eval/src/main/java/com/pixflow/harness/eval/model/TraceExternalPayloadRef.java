package com.pixflow.harness.eval.model;

public record TraceExternalPayloadRef(
        String key,
        long size,
        String etag,
        String sha256,
        String preview,
        boolean missingExternal) {
}
