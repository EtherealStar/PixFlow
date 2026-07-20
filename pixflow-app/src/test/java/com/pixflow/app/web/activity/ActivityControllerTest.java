package com.pixflow.app.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.app.activity.ActivityActions;
import com.pixflow.app.activity.ActivityCommandRouter;
import com.pixflow.app.activity.ActivityKind;
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
}
