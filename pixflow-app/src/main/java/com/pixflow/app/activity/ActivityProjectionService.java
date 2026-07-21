package com.pixflow.app.activity;

import com.pixflow.app.activity.ActivityProjectionRepository.ActivityFilter;
import com.pixflow.app.activity.ActivityProjectionRepository.ActivityPage;
import com.pixflow.app.activity.ActivityProjectionRepository.StoredSource;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ActivityProjectionService {
    private final ActivityProjectionRepository repository;

    public ActivityProjectionService(ActivityProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public synchronized Optional<ActivityFrame> accept(ActivitySourceEvent event) {
        Objects.requireNonNull(event, "event");
        Optional<ActivityProjectionRepository.StoredActivity> current =
                repository.find(event.sourceKind(), event.sourceId());
        if (current.isPresent()) {
            ActivityProjectionRepository.StoredActivity stored = current.orElseThrow();
            if (stored.administratorId() != event.administratorId()) {
                throw new IllegalStateException("activity source ownership cannot change");
            }
            if (event.sourceRevision() <= stored.sourceRevision()) {
                return Optional.empty();
            }
        }
        return switch (event.operation()) {
            case UPSERT -> repository.upsert(event);
            case REMOVE -> repository.remove(event);
        };
    }

    public Optional<ActivityView> get(long administratorId, String activityId) {
        return repository.get(administratorId, activityId);
    }

    public Optional<ActivityCommandTarget> getCommandTarget(long administratorId, String activityId) {
        return repository.getCommandTarget(administratorId, activityId);
    }

    public ActivityPage list(long administratorId, ActivityFilter filter, int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1-based");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        return repository.list(administratorId, filter, page, size);
    }

    public Optional<ActivityFrame> remove(
            long administratorId, ActivitySourceKind sourceKind, String sourceId) {
        Optional<ActivityProjectionRepository.StoredActivity> current = repository.find(sourceKind, sourceId);
        if (current.isEmpty() || current.orElseThrow().administratorId() != administratorId) {
            return Optional.empty();
        }
        long revision = current.orElseThrow().sourceRevision() + 1;
        return accept(new ActivitySourceEvent(administratorId, sourceKind, sourceId, revision,
                ActivityOperation.REMOVE, null));
    }

    public synchronized ReconciliationResult reconcile(
            long administratorId,
            ActivitySourceKind sourceKind,
            Supplier<List<ActivitySourceEvent>> ownerSnapshot) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(ownerSnapshot, "ownerSnapshot");
        List<StoredSource> candidates = repository.currentSources(administratorId, sourceKind);
        List<ActivitySourceEvent> events = List.copyOf(ownerSnapshot.get());
        Set<String> present = new HashSet<>();
        int changed = 0;
        for (ActivitySourceEvent event : events) {
            if (event.administratorId() != administratorId || event.sourceKind() != sourceKind
                    || event.operation() != ActivityOperation.UPSERT) {
                throw new IllegalArgumentException("owner snapshot contains an unrelated activity");
            }
            present.add(event.sourceId());
            if (accept(event).isPresent()) {
                changed++;
            }
        }
        int removed = 0;
        for (StoredSource candidate : candidates) {
            if (!present.contains(candidate.sourceId())) {
                ActivitySourceEvent removal = new ActivitySourceEvent(
                        administratorId, sourceKind, candidate.sourceId(),
                        candidate.sourceRevision() + 1, ActivityOperation.REMOVE, null);
                if (repository.remove(removal).isPresent()) {
                    changed++;
                }
                removed++;
            }
        }
        return new ReconciliationResult(events.size(), changed, removed);
    }

    public record ReconciliationResult(int observed, int changed, int removed) {
    }
}
