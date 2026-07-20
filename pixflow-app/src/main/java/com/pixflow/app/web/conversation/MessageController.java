package com.pixflow.app.web.conversation;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.app.web.conversation.sse.SseTurnSession;
import com.pixflow.app.web.conversation.sse.SseTurnSessionFactory;
import com.pixflow.module.conversation.app.MessageSubmitRequest;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.app.TurnPreparationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/conversations")
public final class MessageController {
    private final TurnPreparationService preparation;

    private final SseTurnSessionFactory sessions;

    public MessageController(TurnPreparationService preparation, SseTurnSessionFactory sessions) {
        this.preparation = preparation;
        this.sessions = sessions;
    }

    @PostMapping(path = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submit(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestBody MessageSubmitRequest request) {
        PreparedTurn prepared = preparation.prepare(principal, conversationId, request);
        SseTurnSession session = sessions.create(prepared);
        session.start();
        return session.emitter();
    }
}
