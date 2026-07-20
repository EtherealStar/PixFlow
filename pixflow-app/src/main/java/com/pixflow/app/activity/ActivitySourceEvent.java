package com.pixflow.app.activity;

import java.util.Objects;

public record ActivitySourceEvent(
        long administratorId,
        ActivitySourceKind sourceKind,
        String sourceId,
        long sourceRevision,
        ActivityOperation operation,
        ActivityView view) {
    public ActivitySourceEvent {
        if (administratorId <= 0) {
            throw new IllegalArgumentException("administratorId must be positive");
        }
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(operation, "operation");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        if (operation == ActivityOperation.UPSERT && view == null) {
            throw new IllegalArgumentException("UPSERT requires a complete view");
        }
        if (operation == ActivityOperation.REMOVE && view != null) {
            throw new IllegalArgumentException("REMOVE must not carry a view");
        }
    }
}
