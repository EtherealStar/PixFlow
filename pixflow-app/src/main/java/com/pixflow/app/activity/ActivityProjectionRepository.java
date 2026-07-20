package com.pixflow.app.activity;

import java.util.List;
import java.util.Optional;

public interface ActivityProjectionRepository {
    Optional<StoredActivity> find(ActivitySourceKind sourceKind, String sourceId);

    Optional<ActivityFrame> upsert(ActivitySourceEvent event);

    Optional<ActivityFrame> remove(ActivitySourceEvent event);

    Optional<ActivityView> get(long administratorId, String activityId);

    ActivityPage list(long administratorId, ActivityFilter filter, int page, int size);

    List<StoredSource> currentSources(long administratorId, ActivitySourceKind sourceKind);

    List<PendingActivityFrame> pending(int limit);

    void markDelivered(long sequence);

    record StoredActivity(long administratorId, long sourceRevision, ActivityView view) {
    }

    record StoredSource(String sourceId, long sourceRevision) {
    }

    record ActivityFilter(ActivityStatus status, ActivityKind kind) {
    }

    record ActivityPage(List<ActivityView> records, long total, long page, long size, long cursor) {
        public ActivityPage {
            records = List.copyOf(records);
        }
    }

    record PendingActivityFrame(long administratorId, ActivityFrame frame) {
    }
}
