package com.pixflow.app.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.pixflow.app.activity.ActivityActions;
import com.pixflow.app.activity.ActivityCommandRouter;
import com.pixflow.app.activity.ActivityKind;
import com.pixflow.app.activity.ActivityCommandTarget;
import com.pixflow.app.activity.ActivityRetryResult;
import com.pixflow.app.activity.ActivitySourceKind;
import com.pixflow.app.activity.ActivityProgress;
import com.pixflow.app.activity.ActivityProjectionRepository.ActivityFilter;
import com.pixflow.app.activity.ActivityProjectionRepository.ActivityPage;
import com.pixflow.app.activity.ActivityProjectionService;
import com.pixflow.app.activity.ActivityStatus;
import com.pixflow.app.activity.ActivityView;
import com.pixflow.infra.auth.context.AuthPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityControllerTest {
    @Test
    void returnsAdministratorScopedSnapshotWithCursorAndOneBasedPage() {
        ActivityProjectionService service = mock(ActivityProjectionService.class);
        ActivityCommandRouter commands = mock(ActivityCommandRouter.class);
        ActivityController controller = new ActivityController(service, commands);
        AuthPrincipal principal = new AuthPrincipal(7L, "admin", "Administrator");
        ActivityView view = new ActivityView("task:42", ActivityKind.PROCESS,
                ActivityStatus.RUNNING, new ActivityProgress(2, 5, 0), "conversation-9",
                null, "42", Instant.parse("2026-07-20T08:00:00Z"), null, null,
                new ActivityActions(true, false, false), 31);
        ActivityPage page = new ActivityPage(List.of(view), 1, 1, 50, 31);
        when(service.list(7, new ActivityFilter(null, null), 1, 50)).thenReturn(page);

        var response = controller.list(principal, 1, 50, null, null);

        assertThat(response.data().records()).containsExactly(view);
        assertThat(response.data().page()).isEqualTo(1);
        assertThat(response.data().cursor()).isEqualTo(31);
    }

    @Test
    void retriesThroughTheOpaqueActivityTarget() {
        ActivityProjectionService service = mock(ActivityProjectionService.class);
        ActivityCommandRouter commands = mock(ActivityCommandRouter.class);
        ActivityController controller = new ActivityController(service, commands);
        AuthPrincipal principal = new AuthPrincipal(7L, "admin", "Administrator");
        ActivityView view = new ActivityView("task:42", ActivityKind.PROCESS,
                ActivityStatus.FAILED, new ActivityProgress(3, 5, 2), "conversation-9",
                null, "42", Instant.parse("2026-07-20T08:00:00Z"), null,
                Instant.parse("2026-07-20T08:05:00Z"), new ActivityActions(false, true, true), 31);
        ActivityCommandTarget target = new ActivityCommandTarget(ActivitySourceKind.TASK, "42", view);
        ActivityRetryResult retried = new ActivityRetryResult("task:42", "task:84", "84", "42");
        when(service.getCommandTarget(7, "task:42")).thenReturn(java.util.Optional.of(target));
        when(commands.retryFailed(target, principal)).thenReturn(retried);

        var response = controller.retryFailed(principal, "task:42");

        assertThat(response.data()).isEqualTo(retried);
        verify(commands).retryFailed(target, principal);
    }
}
