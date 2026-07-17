package com.pixflow.module.conversation.api;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
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
@RequestMapping("/api")
public class MessageController {
    private final TurnPreparationService preparationService;
    private final SseTurnSessionFactory sessionFactory;

    public MessageController(
            TurnPreparationService preparationService,
            SseTurnSessionFactory sessionFactory) {
        this.preparationService = preparationService;
        this.sessionFactory = sessionFactory;
    }

    @PostMapping(path = "/conversations/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submit(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestBody MessageSubmitRequest request) {
        PreparedTurn prepared = preparationService.prepare(principal, conversationId, request);
        SseTurnSession session = sessionFactory.create(prepared);
        session.start();
        return session.emitter();
    }
}
