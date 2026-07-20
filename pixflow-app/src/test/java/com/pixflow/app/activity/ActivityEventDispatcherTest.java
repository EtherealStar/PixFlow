package com.pixflow.app.activity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.app.activity.ActivityProjectionRepository.PendingActivityFrame;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ActivityEventDispatcherTest {
    @Test
    void marksDeliveredOnlyAfterSendingToTheAdministratorUserDestination() {
        ActivityProjectionRepository repository = mock(ActivityProjectionRepository.class);
        AdministratorEligibility eligibility = mock(AdministratorEligibility.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ActivityFrame frame = new ActivityFrame(31, ActivityOperation.REMOVE, "task:42", null);
        when(repository.pending(10)).thenReturn(List.of(new PendingActivityFrame(7, frame)));
        when(eligibility.requireEligible(7)).thenReturn(new AuthPrincipal(7L, "admin", "Administrator"));

        new ActivityEventDispatcher(repository, eligibility, messaging).dispatch(10);

        verify(messaging).convertAndSendToUser("admin", "/queue/activity", frame);
        verify(repository).markDelivered(31);
    }

    @Test
    void retainsTheOutboxFrameWhenStompDeliveryFails() {
        ActivityProjectionRepository repository = mock(ActivityProjectionRepository.class);
        AdministratorEligibility eligibility = mock(AdministratorEligibility.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ActivityFrame frame = new ActivityFrame(31, ActivityOperation.REMOVE, "task:42", null);
        when(repository.pending(10)).thenReturn(List.of(new PendingActivityFrame(7, frame)));
        when(eligibility.requireEligible(7)).thenReturn(new AuthPrincipal(7L, "admin", "Administrator"));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(messaging).convertAndSendToUser("admin", "/queue/activity", frame);

        new ActivityEventDispatcher(repository, eligibility, messaging).dispatch(10);

        verify(repository, never()).markDelivered(31);
    }
}
