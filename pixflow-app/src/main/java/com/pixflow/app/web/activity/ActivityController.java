package com.pixflow.app.web.activity;

import com.pixflow.app.activity.ActivityCommandRouter;
import com.pixflow.app.activity.ActivityKind;
import com.pixflow.app.activity.ActivityProjectionRepository.ActivityFilter;
import com.pixflow.app.activity.ActivityProjectionRepository.ActivityPage;
import com.pixflow.app.activity.ActivityProjectionService;
import com.pixflow.app.activity.ActivityStatus;
import com.pixflow.app.activity.ActivityView;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ActivityController {
    private final ActivityProjectionService activities;

    private final ActivityCommandRouter commands;

    public ActivityController(ActivityProjectionService activities, ActivityCommandRouter commands) {
        this.activities = activities;
        this.commands = commands;
    }

    @GetMapping("/api/activities")
    public ApiResponse<ActivitySnapshot> list(
            @CurrentUser AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(required = false) ActivityKind kind) {
        ActivityPage result = activities.list(principal.userId(), new ActivityFilter(status, kind), page, size);
        return ApiResponse.ok(new ActivitySnapshot(
                result.records(), result.total(), result.page(), result.size(), result.cursor()));
    }

    @GetMapping("/api/activities/{activityId}")
    public ApiResponse<ActivityView> get(
            @CurrentUser AuthPrincipal principal, @PathVariable String activityId) {
        return ApiResponse.ok(requireActivity(principal, activityId));
    }

    @PostMapping("/api/activities/{activityId}/cancel")
    public ApiResponse<Void> cancel(
            @CurrentUser AuthPrincipal principal, @PathVariable String activityId) {
        commands.cancel(requireActivity(principal, activityId), principal);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/activities/{activityId}")
    public ApiResponse<Void> clear(
            @CurrentUser AuthPrincipal principal, @PathVariable String activityId) {
        ActivityView activity = requireActivity(principal, activityId);
        commands.clear(activity, principal);
        ActivitySource source = source(activity);
        activities.remove(principal.userId(), source.kind(), source.id());
        return ApiResponse.ok(null);
    }

    private ActivityView requireActivity(AuthPrincipal principal, String activityId) {
        return activities.get(principal.userId(), activityId).orElseThrow(() ->
                new PixFlowException(CommonErrorCode.RESOURCE_NOT_FOUND, "activity not found"));
    }

    public record ActivitySnapshot(
            List<ActivityView> records,
            long total,
            long page,
            long size,
            long cursor) {
        public ActivitySnapshot {
            records = List.copyOf(records);
        }
    }

    private static ActivitySource source(ActivityView activity) {
        if (activity.taskId() != null) {
            return new ActivitySource(com.pixflow.app.activity.ActivitySourceKind.TASK, activity.taskId());
        }
        int separator = activity.activityId().indexOf(':');
        if (separator < 1 || separator == activity.activityId().length() - 1) {
            throw new IllegalStateException("invalid activity identity");
        }
        String prefix = activity.activityId().substring(0, separator);
        com.pixflow.app.activity.ActivitySourceKind kind = switch (prefix) {
            case "upload" -> com.pixflow.app.activity.ActivitySourceKind.UPLOAD;
            case "package" -> com.pixflow.app.activity.ActivitySourceKind.PACKAGE;
            default -> throw new IllegalStateException("unknown activity identity");
        };
        return new ActivitySource(kind, activity.activityId().substring(separator + 1));
    }

    private record ActivitySource(com.pixflow.app.activity.ActivitySourceKind kind, String id) {
    }
}
