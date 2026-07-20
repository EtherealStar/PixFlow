package com.pixflow.app.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.app.activity.ActivityProjectionRepository.StoredSource;
import com.pixflow.app.activity.ActivityProjectionRepository.StoredActivity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActivityProjectionServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-20T08:00:00Z");

    @Test
    void acceptsOnlyAnOwnerRevisionNewerThanTheStoredProjection() {
        ActivityProjectionRepository repository = mock(ActivityProjectionRepository.class);
        ActivityProjectionService service = new ActivityProjectionService(repository);
        ActivitySourceEvent newer = event(8, view(ActivityStatus.RUNNING));
        ActivityFrame stored = new ActivityFrame(31, ActivityOperation.UPSERT, "task:42",
                newer.view().withSequence(31));
        when(repository.find(ActivitySourceKind.TASK, "42"))
                .thenReturn(Optional.of(new StoredActivity(7, 7, view(ActivityStatus.QUEUED))));
        when(repository.upsert(newer)).thenReturn(Optional.of(stored));

        assertThat(service.accept(newer)).contains(stored);
    }

    @Test
    void ignoresDuplicateAndOutOfOrderOwnerRevisions() {
        ActivityProjectionRepository repository = mock(ActivityProjectionRepository.class);
        ActivityProjectionService service = new ActivityProjectionService(repository);
        when(repository.find(ActivitySourceKind.TASK, "42"))
                .thenReturn(Optional.of(new StoredActivity(7, 8, view(ActivityStatus.RUNNING))));

        assertThat(service.accept(event(8, view(ActivityStatus.FAILED)))).isEmpty();
        assertThat(service.accept(event(6, view(ActivityStatus.QUEUED)))).isEmpty();
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).remove(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconciliationRemovesOnlySourcesThatExistedBeforeTheOwnerSnapshot() {
        ActivityProjectionRepository repository = mock(ActivityProjectionRepository.class);
        ActivityProjectionService service = new ActivityProjectionService(repository);
        ActivitySourceEvent current = event(8, view(ActivityStatus.RUNNING));
        ActivitySourceEvent staleRemoval = new ActivitySourceEvent(
                7, ActivitySourceKind.TASK, "41", 6, ActivityOperation.REMOVE, null);
        when(repository.currentSources(7, ActivitySourceKind.TASK))
                .thenReturn(List.of(new StoredSource("41", 5), new StoredSource("42", 7)));
        when(repository.find(ActivitySourceKind.TASK, "42"))
                .thenReturn(Optional.of(new StoredActivity(7, 7, view(ActivityStatus.QUEUED))));

        service.reconcile(7, ActivitySourceKind.TASK, () -> List.of(current));

        verify(repository).upsert(current);
        verify(repository).remove(staleRemoval);
    }

    private static ActivitySourceEvent event(long revision, ActivityView view) {
        return new ActivitySourceEvent(7, ActivitySourceKind.TASK, "42", revision,
                ActivityOperation.UPSERT, view);
    }

    private static ActivityView view(ActivityStatus status) {
        return new ActivityView("task:42", ActivityKind.PROCESS, status,
                new ActivityProgress(2, 5, 0), "conversation-9", null, "42", CREATED_AT,
                null, null, new ActivityActions(true, false, false), 0);
    }
}
