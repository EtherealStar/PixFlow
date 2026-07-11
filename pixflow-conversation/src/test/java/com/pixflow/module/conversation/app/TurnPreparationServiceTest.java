package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TurnPreparationServiceTest {
    @Test
    void preparesOwnerAwareRequestAndTransfersLockOwnership() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationLock conversationLock = mock(ConversationLock.class);
        TurnLockHandle lockHandle = mock(TurnLockHandle.class);
        AtomicReference<AgentTurnRequest> captured = new AtomicReference<>();
        AgentTurnRunner runner = (request, sink) -> {
            captured.set(request);
            return "ok";
        };
        when(conversationLock.tryLock("conv-1")).thenReturn(Optional.of(lockHandle));
        TurnPreparationService service = new TurnPreparationService(
                conversationService,
                conversationLock,
                null,
                new AttachmentMapper(),
                AgentTurnRunnerRegistry.of(runner));

        PreparedTurn prepared = service.prepare(
                7L,
                "conv-1",
                new MessageSubmitRequest("hello", List.of(), null, Map.of()));

        InOrder order = inOrder(conversationService, conversationLock);
        order.verify(conversationService).requireActive(7L, "conv-1");
        order.verify(conversationLock).tryLock("conv-1");
        assertThat(prepared.execute(event -> { }, CancellationToken.NONE)).isEqualTo("ok");
        assertThat(captured.get().conversationId()).isEqualTo("conv-1");
        assertThat(captured.get().prompt()).isEqualTo("hello");
        verify(lockHandle, times(0)).close();

        prepared.close();
        prepared.close();
        verify(lockHandle, times(1)).close();
    }

    @Test
    void releasesLockWhenPostLockPreparationFails() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationLock conversationLock = mock(ConversationLock.class);
        TurnLockHandle lockHandle = mock(TurnLockHandle.class);
        AgentTurnRunnerRegistry registry = mock(AgentTurnRunnerRegistry.class);
        when(conversationLock.tryLock("conv-1")).thenReturn(Optional.of(lockHandle));
        when(registry.resolve()).thenThrow(new IllegalStateException("runner failed"));
        TurnPreparationService service = new TurnPreparationService(
                conversationService,
                conversationLock,
                null,
                new AttachmentMapper(),
                registry);

        assertThatThrownBy(() -> service.prepare(
                7L,
                "conv-1",
                new MessageSubmitRequest("hello", List.of(), null, Map.of())))
                .isInstanceOf(IllegalStateException.class);

        verify(lockHandle).close();
    }
}
