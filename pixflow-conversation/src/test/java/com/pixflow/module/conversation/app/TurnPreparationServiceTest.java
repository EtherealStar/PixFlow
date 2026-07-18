package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TurnPreparationServiceTest {
    @Test
    void preparesOwnerAwareRequestAndTransfersLockOwnership() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationLock conversationLock = mock(ConversationLock.class);
        MessageReferenceValidator referenceValidator = mock(MessageReferenceValidator.class);
        TurnLockHandle lockHandle = mock(TurnLockHandle.class);
        AtomicReference<AgentTurnRequest> captured = new AtomicReference<>();
        AgentTurnRunner runner = (request, sink) -> {
            captured.set(request);
            return "ok";
        };
        List<MessageReference> references = List.of(
                new MessageReference("package:1", "summer.zip"));
        when(referenceValidator.validate(
                new PermissionPrincipal("7", "admin"), "conv-1", List.of()))
                .thenReturn(references);
        when(conversationLock.tryLock("conv-1")).thenReturn(Optional.of(lockHandle));
        TurnPreparationService service = new TurnPreparationService(
                conversationService,
                conversationLock,
                referenceValidator,
                AgentTurnRunnerRegistry.of(runner));

        PreparedTurn prepared = service.prepare(
                principal(),
                "conv-1",
                new MessageSubmitRequest("hello", List.of()));

        InOrder order = inOrder(conversationService, referenceValidator, conversationLock);
        order.verify(conversationService).requireActive(7L, "conv-1");
        order.verify(referenceValidator).validate(
                new PermissionPrincipal("7", "admin"), "conv-1", List.of());
        order.verify(conversationLock).tryLock("conv-1");
        assertThat(prepared.execute(event -> { }, CancellationToken.NONE)).isEqualTo("ok");
        assertThat(captured.get().conversationId()).isEqualTo("conv-1");
        assertThat(captured.get().prompt()).isEqualTo("hello");
        assertThat(captured.get().references()).containsExactlyElementsOf(references);
        verify(lockHandle, times(0)).close();

        prepared.close();
        prepared.close();
        verify(lockHandle, times(1)).close();
    }

    @Test
    void releasesLockWhenPostLockPreparationFails() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationLock conversationLock = mock(ConversationLock.class);
        MessageReferenceValidator referenceValidator = mock(MessageReferenceValidator.class);
        TurnLockHandle lockHandle = mock(TurnLockHandle.class);
        AgentTurnRunnerRegistry registry = mock(AgentTurnRunnerRegistry.class);
        when(conversationLock.tryLock("conv-1")).thenReturn(Optional.of(lockHandle));
        when(referenceValidator.validate(
                new PermissionPrincipal("7", "admin"), "conv-1", List.of()))
                .thenReturn(List.of());
        when(registry.resolve()).thenThrow(new IllegalStateException("runner failed"));
        TurnPreparationService service = new TurnPreparationService(
                conversationService,
                conversationLock,
                referenceValidator,
                registry);

        assertThatThrownBy(() -> service.prepare(
                principal(),
                "conv-1",
                new MessageSubmitRequest("hello", List.of())))
                .isInstanceOf(IllegalStateException.class);

        verify(lockHandle).close();
    }

    @Test
    void validationFailureDoesNotAcquireTurnLock() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationLock conversationLock = mock(ConversationLock.class);
        MessageReferenceValidator referenceValidator = mock(MessageReferenceValidator.class);
        when(referenceValidator.validate(
                new PermissionPrincipal("7", "admin"), "conv-1", List.of()))
                .thenThrow(new IllegalArgumentException("invalid"));
        TurnPreparationService service = new TurnPreparationService(
                conversationService,
                conversationLock,
                referenceValidator,
                AgentTurnRunnerRegistry.of((request, sink) -> "ok"));

        assertThatThrownBy(() -> service.prepare(
                principal(), "conv-1", new MessageSubmitRequest("hello", List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        verify(conversationLock, times(0)).tryLock("conv-1");
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(7L, "admin", "Admin");
    }
}
