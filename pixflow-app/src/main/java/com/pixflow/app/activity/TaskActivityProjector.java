package com.pixflow.app.activity;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.task.api.activity.TaskActivitySnapshot;
import com.pixflow.module.task.api.activity.TaskActivitySource;
import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.api.event.TaskCompletedEvent;
import com.pixflow.module.task.api.event.TaskCreatedEvent;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public final class TaskActivityProjector {
    private static final int RECONCILIATION_PAGE_SIZE = 100;

    private final TaskActivitySource source;

    private final AdministratorEligibility eligibility;

    private final ActivityProjectionService projection;

    public TaskActivityProjector(
            TaskActivitySource source,
            AdministratorEligibility eligibility,
            ActivityProjectionService projection) {
        this.source = Objects.requireNonNull(source, "source");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @EventListener
    public void onCreated(TaskCreatedEvent event) {
        project(event.taskId());
    }

    @EventListener
    public void onProgress(ProgressEvent event) {
        project(event.taskId());
    }

    @EventListener
    public void onCompleted(TaskCompletedEvent event) {
        project(event.taskId());
    }

    @Scheduled(fixedDelayString = "${pixflow.activity.reconciliation-interval:30s}")
    public void reconcile() {
        AuthPrincipal administrator = eligibility.current();
        projection.reconcile(administrator.userId(), ActivitySourceKind.TASK,
                () -> snapshotEvents(administrator));
    }

    private List<ActivitySourceEvent> snapshotEvents(AuthPrincipal administrator) {
        List<ActivitySourceEvent> events = new ArrayList<>();
        int page = 1;
        long visited = 0;
        long total;
        do {
            PageResult<TaskActivitySnapshot> snapshots =
                    source.listCurrent(page, RECONCILIATION_PAGE_SIZE);
            snapshots.records().stream()
                    .map(snapshot -> event(administrator, snapshot))
                    .forEach(events::add);
            visited += snapshots.records().size();
            total = snapshots.total();
            page++;
        } while (visited < total);
        return events;
    }

    private void project(String taskId) {
        AuthPrincipal administrator = eligibility.current();
        source.find(taskId).ifPresent(snapshot -> accept(administrator, snapshot));
    }

    private void accept(AuthPrincipal administrator, TaskActivitySnapshot snapshot) {
        projection.accept(event(administrator, snapshot));
    }

    private static ActivitySourceEvent event(
            AuthPrincipal administrator, TaskActivitySnapshot snapshot) {
        ActivityOperation operation = snapshot.status() == TaskStatus.CANCELLED
                ? ActivityOperation.REMOVE : ActivityOperation.UPSERT;
        ActivityView view = operation == ActivityOperation.REMOVE ? null : toView(snapshot);
        return new ActivitySourceEvent(
                administrator.userId(), ActivitySourceKind.TASK, snapshot.taskId(), snapshot.revision(),
                operation, view);
    }

    private static ActivityView toView(TaskActivitySnapshot snapshot) {
        int total = snapshot.total();
        int completed = Math.min(snapshot.completed(), total);
        return new ActivityView(
                "task:" + snapshot.taskId(),
                snapshot.taskType() == TaskType.IMAGE_GEN ? ActivityKind.IMAGEGEN : ActivityKind.PROCESS,
                status(snapshot.status()),
                new ActivityProgress(completed, total, snapshot.failed()),
                snapshot.conversationId(), Long.toString(snapshot.packageId()), snapshot.taskId(),
                snapshot.createdAt(), snapshot.startedAt(), snapshot.finishedAt(),
                new ActivityActions(snapshot.cancellable(), snapshot.retryable(), snapshot.clearable()), 0);
    }

    private static ActivityStatus status(TaskStatus status) {
        return switch (status) {
            case PENDING, QUEUED -> ActivityStatus.QUEUED;
            case RUNNING -> ActivityStatus.RUNNING;
            case COMPLETED -> ActivityStatus.SUCCEEDED;
            case PARTIAL -> ActivityStatus.PARTIALLY_SUCCEEDED;
            case FAILED -> ActivityStatus.FAILED;
            case CANCELLED -> throw new IllegalArgumentException("cancelled task must be removed");
        };
    }
}
