package com.pixflow.module.conversation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.app.MessageSubmitRequest;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.app.TurnPreparationService;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MessageControllerLifecycleTest {
    @Test
    void preparationFailureEscapesBeforeAnEmitterIsReturned() {
        TurnPreparationService preparation = mock(TurnPreparationService.class);
        SseTurnSessionFactory factory = mock(SseTurnSessionFactory.class);
        MessageSubmitRequest request = new MessageSubmitRequest("q", List.of(), null, Map.of());
        when(preparation.prepare(principal(), "missing", request)).thenThrow(
                new PixFlowException(ConversationErrorCode.CONVERSATION_NOT_FOUND, "missing"));
        MessageController controller = new MessageController(preparation, factory);

        assertThatThrownBy(() -> controller.submit(principal(), "missing", request))
                .isInstanceOf(PixFlowException.class)
                .satisfies(error -> assertThat(((PixFlowException) error).code())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
    }

    @Test
    void successfulPreparationStartsSessionAndReturnsItsEmitter() {
        TurnPreparationService preparation = mock(TurnPreparationService.class);
        SseTurnSessionFactory factory = mock(SseTurnSessionFactory.class);
        PreparedTurn prepared = mock(PreparedTurn.class);
        SseTurnSession session = mock(SseTurnSession.class);
        SseEmitter emitter = new SseEmitter();
        MessageSubmitRequest request = new MessageSubmitRequest("q", List.of(), null, Map.of());
        when(preparation.prepare(principal(), "conv-1", request)).thenReturn(prepared);
        when(factory.create(prepared)).thenReturn(session);
        when(session.emitter()).thenReturn(emitter);
        MessageController controller = new MessageController(preparation, factory);

        assertThat(controller.submit(principal(), "conv-1", request)).isSameAs(emitter);
        verify(session).start();
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(7L, "user", "User");
    }
}
