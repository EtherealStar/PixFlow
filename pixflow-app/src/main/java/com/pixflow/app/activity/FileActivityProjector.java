package com.pixflow.app.activity;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.file.api.activity.FileActivitySnapshot;
import com.pixflow.module.file.api.activity.FileActivityPage;
import com.pixflow.module.file.api.activity.FileActivitySource;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public class FileActivityProjector {
    private static final int RECONCILIATION_PAGE_SIZE = 100;

    private final FileActivitySource source;

    private final AdministratorEligibility eligibility;

    private final ActivityProjectionService projection;

    public FileActivityProjector(
            FileActivitySource source,
            AdministratorEligibility eligibility,
            ActivityProjectionService projection) {
        this.source = Objects.requireNonNull(source, "source");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Scheduled(fixedDelayString = "${pixflow.activity.reconciliation-interval:30s}")
    public void reconcile() {
        AuthPrincipal administrator = eligibility.current();
        List<FileActivitySnapshot> snapshots = snapshots();
        reconcile(administrator, ActivitySourceKind.UPLOAD, snapshots);
        reconcile(administrator, ActivitySourceKind.PACKAGE, snapshots);
    }

    private List<FileActivitySnapshot> snapshots() {
        List<FileActivitySnapshot> snapshots = new ArrayList<>();
        int page = 1;
        long visited = 0;
        long total;
        do {
            FileActivityPage current = source.listCurrent(page, RECONCILIATION_PAGE_SIZE);
            snapshots.addAll(current.records());
            visited += current.records().size();
            total = current.total();
            page++;
        } while (visited < total);
        return snapshots;
    }

    private void reconcile(
            AuthPrincipal administrator,
            ActivitySourceKind sourceKind,
            List<FileActivitySnapshot> snapshots) {
        projection.reconcile(administrator.userId(), sourceKind, () -> snapshots.stream()
                .filter(snapshot -> sourceKind(snapshot.sourceKind()) == sourceKind)
                .map(snapshot -> event(administrator, snapshot))
                .toList());
    }

    private void accept(AuthPrincipal administrator, FileActivitySnapshot snapshot) {
        projection.accept(event(administrator, snapshot));
    }

    private static ActivitySourceEvent event(
            AuthPrincipal administrator, FileActivitySnapshot snapshot) {
        ActivitySourceKind sourceKind = sourceKind(snapshot.sourceKind());
        ActivityView view = new ActivityView(
                activityId(sourceKind, snapshot.sourceId()),
                ActivityKind.UPLOAD,
                status(snapshot),
                new ActivityProgress(Math.min(snapshot.completed(), snapshot.total()), snapshot.total(), 0),
                null,
                snapshot.packageId() == null ? null : snapshot.packageId().toString(),
                null,
                snapshot.createdAt(),
                snapshot.status() == com.pixflow.module.file.api.activity.FileActivityStatus.UPLOADING
                        ? snapshot.updatedAt() : snapshot.createdAt(),
                snapshot.status() == com.pixflow.module.file.api.activity.FileActivityStatus.SUCCEEDED
                        || snapshot.status()
                                == com.pixflow.module.file.api.activity.FileActivityStatus.PARTIALLY_SUCCEEDED
                        || snapshot.status() == com.pixflow.module.file.api.activity.FileActivityStatus.FAILED
                        ? snapshot.updatedAt() : null,
                new ActivityActions(snapshot.cancellable(), false, snapshot.clearable()), 0);
        return new ActivitySourceEvent(
                administrator.userId(),
                sourceKind,
                snapshot.sourceId(), snapshot.revision(), ActivityOperation.UPSERT, view);
    }

    private static ActivitySourceKind sourceKind(FileActivitySourceKind sourceKind) {
        return sourceKind == FileActivitySourceKind.UPLOAD
                ? ActivitySourceKind.UPLOAD : ActivitySourceKind.PACKAGE;
    }

    private static String activityId(ActivitySourceKind sourceKind, String sourceId) {
        return sourceKind.name().toLowerCase(java.util.Locale.ROOT) + ":" + sourceId;
    }

    private static ActivityStatus status(FileActivitySnapshot snapshot) {
        return switch (snapshot.status()) {
            case UPLOADING -> ActivityStatus.UPLOADING;
            case EXTRACTING -> ActivityStatus.EXTRACTING;
            case SUCCEEDED -> ActivityStatus.SUCCEEDED;
            case PARTIALLY_SUCCEEDED -> ActivityStatus.PARTIALLY_SUCCEEDED;
            case FAILED -> ActivityStatus.FAILED;
        };
    }
}
